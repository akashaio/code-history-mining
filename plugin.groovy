import com.intellij.openapi.diff.impl.fragments.LineFragment
import com.intellij.openapi.diff.impl.processing.TextCompareProcessor
import com.intellij.openapi.diff.impl.util.TextDiffTypeEnum
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.FilePathImpl
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileFactory

import static com.intellij.openapi.diff.impl.ComparisonPolicy.IGNORE_SPACE
import static com.intellij.openapi.diff.impl.util.TextDiffTypeEnum.*
import static intellijeval.PluginUtil.*

if (isIdeStartup) return

def file = currentFileIn(project)
def (errorMessage, List<VcsFileRevision> revisions) = tryToGetHistoryFor(file, project)
if (errorMessage != null) {
	show(errorMessage)
	return
}
show(revisions.collect{it.commitMessage}.join("<br/>\n"))

def revisionPairs = (0..<revisions.size() - 1).collect{revisions[it, it + 1]}
def compareProcessor = new TextCompareProcessor(IGNORE_SPACE)
def changesByRevision = revisionPairs.collectMany { VcsFileRevision before, VcsFileRevision after ->
	def changedFragments = compareProcessor.process(new String(before.content), new String(after.content)).findAll{ it.type != null }
	changedFragments.collect{ fragment -> fragment.type == DELETED ? [before, fragment] : [after, fragment] }
}.groupBy{it[0]}
show(changesByRevision.keySet().join("<br/>"))

def psiFileFactory = PsiFileFactory.getInstance(project)
changesByRevision.each { VcsFileRevision revision, Collection<LineFragment> fragments ->
	def psiFile = psiFileFactory.createFileFromText(file.name, file.fileType, new String(revision.content))
//	show(psiFile.name)
}

show("good to go")


static def tryToGetHistoryFor(VirtualFile file, Project project) {
	if (file == null) return ["Virtual file was null"]

	AbstractVcs activeVcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file)
	if (activeVcs == null) return ["There is no history for '${file.name}'"]

	def historySession = activeVcs.vcsHistoryProvider.createSessionFor(new FilePathImpl(file))
	def revisions = historySession.revisionList.sort{ it.revisionDate }
	if (revisions.size() < 2) return ["There is only one revision for '${file.name}'"]

	def noErrors = null
	[noErrors, revisions]
}

void testTextCompare() {
	try {
		new TextCompareProcessor(IGNORE_SPACE).with {
			process("", "").with {
				assert size() == 0
			}

			// single-line diffs
			process("a", "").with {
				assert size() == 1
				assertFragment(first(), DELETED, [0, 1], [0, 0])
			}
			process("", "a").with {
				assert size() == 1
				assertFragment(first(), INSERT, [0, 0], [0, 1])
			}
			process("a", "a").with {
				assert size() == 1
				assertFragment(first(), null, [0, 1], [0, 1])
			}
			process("abc", "ac").with {
				assert size() == 1
				assertFragment(first(), CHANGED, [0, 1], [0, 1])
			}
			process("ac", "abc").with {
				assert size() == 1
				assertFragment(first(), CHANGED, [0, 1], [0, 1])
			}
			process("abc", "bc").with {
				assert size() == 1
				assertFragment(first(), CHANGED, [0, 1], [0, 1])
			}

			// two-line diffs
			process("abc\ndef", "abc\ndef").with {
				assert size() == 1
				assertFragment(first(), null, [0, 2], [0, 2])
			}
			process("abc\ndef", "abc").with {
				assert size() == 2
				assertFragment(get(0), null, [0, 1], [0, 1])
				assertFragment(get(1), DELETED, [1, 2], [1, 1])
			}
			process("abc", "abc\ndef").with {
				assert size() == 2
				assertFragment(get(0), null, [0, 1], [0, 1])
				assertFragment(get(1), INSERT, [1, 1], [1, 2])
			}
			process("abc\ndf", "abc\ndef").with {
				assert size() == 2
				assertFragment(get(0), null, [0, 1], [0, 1])
				assertFragment(get(1), CHANGED, [1, 2], [1, 2])
			}
			process("abc\ndef", "abc\ndf").with {
				assert size() == 2
				assertFragment(get(0), null, [0, 1], [0, 1])
				assertFragment(get(1), CHANGED, [1, 2], [1, 2])
			}

			// three-line diffs
			process("abc\ndef\nghi", "abc\ndef\nghi").with {
				assert size() == 1
				assertFragment(get(0), null, [0, 3], [0, 3])
			}
			process("abc\ndef\nghi", "abc\ndef").with {
				assert size() == 2
				assertFragment(get(0), null, [0, 2], [0, 2])
				assertFragment(get(1), DELETED, [2, 3], [2, 2])
			}
			process("abc\ndef\nghi", "abc\nghi").with {
				assert size() == 3
				assertFragment(get(0), null, [0, 1], [0, 1])
				assertFragment(get(1), DELETED, [1, 2], [1, 1])
				assertFragment(get(2), null, [2, 3], [1, 2])
			}
			process("abc\ndef\nghi", "def\nghi").with {
				assert size() == 2
				assertFragment(get(0), DELETED, [0, 1], [0, 0])
				assertFragment(get(1), null, [1, 3], [0, 2])
			}
			process("abc\ndef\nghi", "abc\nghi\ndef").with {
				assert size() == 4
				assertFragment(get(0), null, [0, 1], [0, 1])
				assertFragment(get(1), DELETED, [1, 2], [1, 1])
				assertFragment(get(2), null, [2, 3], [1, 2])
				assertFragment(get(3), INSERT, [3, 3], [2, 3])
			}
		}

		new TextCompareProcessor(IGNORE_SPACE).with {
			def fragments = process("abc\ndef\nghi", "abc\nghi\ndef")
			assert asDiffInfo(fragments) == [new TextDiffInfo(DELETED, 1, 2), new TextDiffInfo(INSERT, 2, 3)]
		}
		showInConsole("OK...", "TextCompareProcessorTest", project)

	} catch (AssertionError assertionError) {
		def writer = new StringWriter()
		assertionError.printStackTrace(new PrintWriter(writer))
		showInConsole(writer.buffer.toString(), "TextCompareProcessorTest", project)
	}
}

static Collection<TextDiffInfo> asDiffInfo(List<LineFragment> fragments) {
	fragments.findAll{it.type != null}.collect{
		int startLine = (it.type == DELETED ? it.startingLine1 : it.startingLine2)
		int endLine = (it.type == DELETED ? it.endLine1 : it.endLine2)
		new TextDiffInfo(it.type, startLine, endLine)
	}
}

@groovy.transform.Immutable
final class TextDiffInfo {
	final TextDiffTypeEnum diffType
	final int startLine
	final int endLine
}

static assertFragment(LineFragment fragment, TextDiffTypeEnum diffType, leftRange, rightRange) {
	fragment.with {
		assert type == diffType
		assert [startingLine1, endLine1] == leftRange
		assert [startingLine2, endLine2] == rightRange
	}
}