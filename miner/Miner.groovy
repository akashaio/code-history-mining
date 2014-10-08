package miner
import codemining.core.analysis.Context
import codemining.core.analysis.Visualization
import codemining.core.common.langutil.DateRange
import codemining.core.common.langutil.Measure
import codemining.core.historystorage.EventStorage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.text.DateFormatUtil
import historystorage.HistoryGrabberConfig
import historystorage.HistoryStorage
import miner.ui.UI
import vcsaccess.ChangeEventsReader
import vcsaccess.VcsAccess
import vcsreader.Commit

import static codemining.core.common.langutil.DateTimeUtil.dateRange
import static codemining.core.common.langutil.DateTimeUtil.floorToDay

class Miner {
	private final UI ui
	private final HistoryStorage storage
	private final VcsAccess vcsAccess
	private final Measure measure
	private final MinerLog log
	private volatile boolean grabHistoryIsInProgress

	Miner(UI ui, HistoryStorage storage, VcsAccess vcsAccess, Measure measure, MinerLog log = null) {
		this.ui = ui
		this.storage = storage
		this.vcsAccess = vcsAccess
		this.measure = measure
		this.log = log
	}

	def createVisualization(File file, Visualization visualization) {
		ui.runInBackground("Creating ${visualization.name.toLowerCase()}") { ProgressIndicator indicator ->
			try {
				measure.start()

				def projectName = storage.guessProjectNameFrom(file.name)
				def checkIfCancelled = CancelledException.check(indicator)

				def events = storage.readAllEvents(file.name, checkIfCancelled)
				def context = new Context(events, projectName, checkIfCancelled, { String message ->
                    Logger.getInstance("CodeHistoryMining").info(message)
                })
				def html = visualization.generate(context)

				ui.showInBrowser(html, projectName, visualization)

				measure.forEachDuration{ log?.measuredDuration(it) }
			} catch (CancelledException ignored) {
				log?.cancelledBuilding(visualization.name)
			}
		}
	}

	@SuppressWarnings("GrMethodMayBeStatic")
	def fileCountByFileExtension(Project project) {
		def scope = GlobalSearchScope.projectScope(project)
		FileTypeManager.instance.registeredFileTypes.inject([:]) { Map map, FileType fileType ->
			int fileCount = FileBasedIndex.instance.getContainingFiles(FileTypeIndex.NAME, fileType, scope).size()
			if (fileCount > 0) map.put(fileType.defaultExtension, fileCount)
			map
		}.sort{ -it.value }
	}

	def onProjectOpened(Project project) {
		def grabberConfig = storage.loadGrabberConfigFor(project.name)
		if (grabberConfig.grabOnVcsUpdate)
			vcsAccess.addVcsUpdateListenerFor(project.name, this.&grabHistoryOnVcsUpdate)
	}

	def onProjectClosed(Project project) {
		vcsAccess.removeVcsUpdateListenerFor(project.name)
	}

	def grabHistoryOf(Project project) {
		if (grabHistoryIsInProgress) return ui.showGrabbingInProgressMessage(project)
		if (vcsAccess.noVCSRootsIn(project)) return ui.showNoVcsRootsMessage(project)

		def saveConfig = { HistoryGrabberConfig userInput ->
			storage.saveGrabberConfigFor(project.name, userInput)
		}

		def grabberConfig = storage.loadGrabberConfigFor(project.name)
		ui.showGrabbingDialog(grabberConfig, project, saveConfig) { HistoryGrabberConfig userInput ->
			saveConfig(userInput)

			if (userInput.grabOnVcsUpdate)
				vcsAccess.addVcsUpdateListenerFor(project.name, this.&grabHistoryOnVcsUpdate)
			else
				vcsAccess.removeVcsUpdateListenerFor(project.name)

			grabHistoryIsInProgress = true
			ui.runInBackground("Grabbing project history") { ProgressIndicator indicator ->
				try {
					measure.start()
					measure.measure("Total time") {
						def eventStorage = storage.eventStorageFor(userInput.outputFilePath)
						def eventsReader = vcsAccess.changeEventsReaderFor(project, userInput.grabChangeSizeInLines)
                        def requestDateRange = dateRange(userInput.from, userInput.to)

                        def message = doGrabHistory(eventsReader, eventStorage, requestDateRange, indicator)

						ui.showGrabbingFinishedMessage(message.text, message.title, project)
					}
					measure.forEachDuration{ log?.measuredDuration(it) }
				} finally {
					grabHistoryIsInProgress = false
				}
			}
		}
	}

	def grabHistoryOnVcsUpdate(Project project, Date today = floorToDay(new Date())) {
		if (grabHistoryIsInProgress) return
		def config = storage.loadGrabberConfigFor(project.name)
		if (floorToDay(config.lastGrabTime) == today) return

		grabHistoryIsInProgress = true
		ui.runInBackground("Grabbing project history") { ProgressIndicator indicator ->
			try {
				def eventStorage = storage.eventStorageFor(config.outputFilePath)
				def eventsReader = vcsAccess.changeEventsReaderFor(project, config.grabChangeSizeInLines)
                def requestDateRange = dateRange(eventStorage.storedDateRange().to, today)

				doGrabHistory(eventsReader, eventStorage, requestDateRange, indicator)

				storage.saveGrabberConfigFor(project.name, config.withLastGrabTime(today))
			} finally {
				grabHistoryIsInProgress = false
			}
		}
	}

	private doGrabHistory(ChangeEventsReader eventsReader, EventStorage eventStorage, DateRange requestDateRange, indicator = null) {
		def updateIndicatorText = { Commit commit, callback ->
			def date = DateFormatUtil.dateFormat.format((Date) commit.commitDate)

            log?.processingChangeList(date + " - " + commit.revision + " - " + commit.comment.trim())
            indicator?.text = "Grabbing project history (${date} - '${commit.comment.trim()}')"

			callback()

			indicator?.text = "Grabbing project history (${date} - looking for next commit...)"
		}
		def isCancelled = { indicator?.canceled }
        def loadProjectHistory = { DateRange dateRange ->
            log?.loadingProjectHistory(dateRange.from, dateRange.to)
            eventsReader.readPresentToPast(dateRange.from, dateRange.to, isCancelled, updateIndicatorText) { commitChangeEvents ->
                eventStorage.addEvents(commitChangeEvents)
            }
            eventStorage.flush()
        }

        def dateRanges = requestDateRange.subtract(eventStorage.storedDateRange())
        if (dateRanges.first != null) loadProjectHistory(dateRanges.first)
        if (dateRanges.second != null) loadProjectHistory(dateRanges.second)

		def messageText = ""
		if (eventStorage.hasNoEvents()) {
			messageText += "Grabbed history to ${eventStorage.filePath}\n"
			messageText += "However, it has nothing in it probably because there are no commits from $config.from to $config.to\n"
		} else {
			messageText += "Grabbed history to ${eventStorage.filePath}\n"
			messageText += "It should have history from '${eventStorage.storedDateRange().from}' to '${eventStorage.storedDateRange().to}'.\n"
		}
		if (eventsReader.lastRequestHadErrors) {
			messageText += "\nThere were errors while reading commits from VCS, please check IDE log for details.\n"
		}
		[text: messageText, title: "Code History Mining"]
	}
}

interface MinerLog {
	def loadingProjectHistory(Date fromDate, Date toDate)

	def processingChangeList(String changeListName)

	def cancelledBuilding(String visualizationName)

	def measuredDuration(def entry)
}
