package codemining.historystorage
import com.intellij.openapi.util.io.FileUtil
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Immutable

import java.text.ParseException
import java.text.SimpleDateFormat

@Immutable
class HistoryGrabberConfig {
	Date from
	Date to
	String outputFilePath
	boolean grabChangeSizeInLines
	boolean grabOnVcsUpdate
	Date lastGrabTime

	HistoryGrabberConfig withLastGrabTime(Date updatedLastGrabTime) {
		new HistoryGrabberConfig(from, to, outputFilePath, grabChangeSizeInLines, grabOnVcsUpdate, updatedLastGrabTime)
	}

	HistoryGrabberConfig withOutputFilePath(String newOutputFilePath) {
		new HistoryGrabberConfig(from, to, newOutputFilePath, grabChangeSizeInLines, grabOnVcsUpdate, lastGrabTime)
	}

	static defaultConfig() {
		new HistoryGrabberConfig(new Date() - 300, new Date(), "", false, false, new Date(1))
	}

	static HistoryGrabberConfig loadGrabberConfigFor(String projectName, String pathToFolder, Closure<HistoryGrabberConfig> createDefault) {
		def stateByProject = loadStateByProject(pathToFolder)
		def result = stateByProject.get(projectName)
		result != null ? result : createDefault()
	}

	static saveGrabberConfigOf(String projectName, String pathToFolder, HistoryGrabberConfig grabberConfig) {
		def stateByProject = loadStateByProject(pathToFolder)
		stateByProject.put(projectName, grabberConfig)
		FileUtil.writeToFile(new File(pathToFolder + "/grabber-config.json"), JsonOutput.toJson(stateByProject))

		def oldFile = new File(pathToFolder + "/dialog-state.json")
		if (oldFile.exists()) FileUtil.delete(oldFile)
	}

	private static Map<String, HistoryGrabberConfig> loadStateByProject(String pathToFolder) {
		try {
			def parseBoolean = { Boolean.parseBoolean(it?.toString()) }
			def toGrabberConfig = { map -> new HistoryGrabberConfig(
					parseDate(map.from),
					parseDate(map.to),
					map.outputFilePath,
					parseBoolean(map.grabChangeSizeInLines),
					parseBoolean(map.grabOnVcsUpdate),
					parseDate(map.lastGrabTime)
			)}

			def json = readConfigFile(pathToFolder)
			new JsonSlurper().parseText(json).collectEntries{ [it.key, toGrabberConfig(it.value)] }

		} catch (Exception ignored) {
			[:]
		}
	}

	private static Date parseDate(String s) {
		if (s == null) new Date(0)
		else {
			try {
				new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").parse(s)
			} catch (ParseException ignored) {
				new Date(0)
			}
		}
	}

	private static String readConfigFile(String pathToFolder) {
		def oldFile = new File(pathToFolder + "/dialog-state.json")
		if (oldFile.exists()) {
			FileUtil.loadFile(oldFile)
		} else {
			FileUtil.loadFile(new File(pathToFolder + "/grabber-config.json"))
		}
	}
}
