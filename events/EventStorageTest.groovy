package events
import org.junit.Test

import static org.hamcrest.CoreMatchers.equalTo
import static org.junit.Assert.assertThat
import static util.DateTimeUtil.exactDateTime

class EventStorageTest {

	@Test void "should append events to a file"() {
		storage.appendToEventsFile([event1])
		assertThat(new File(temporaryFile).readLines().join("\n"), equalTo(event1AsCsv))

		storage.appendToEventsFile([event2])
		assertThat(new File(temporaryFile).readLines().join("\n").trim(), equalTo(
				event1AsCsv + "\n" +
				event2AsCsv
		))
	}

	@Test void "should prepend events to a file"() {
		storage.prependToEventsFile([event1, event2])
		assertThat(new File(temporaryFile).readLines().join("\n"), equalTo(
				event1AsCsv + "\n" +
				event2AsCsv
		))

		storage.prependToEventsFile([event1])
		assertThat(new File(temporaryFile).readLines().join("\n"), equalTo(
				event1AsCsv + "\n" +
				event1AsCsv + "\n" +
				event2AsCsv
		))
	}

	@Test void "should read all events from file"() {
		new File(temporaryFile).write(event1AsCsv + "\n" + event2AsCsv)
		storage.readAllEvents().with{
			assert it[0] == event1
			assert it[1] == event2
		}
	}

	@Test void "should read time of most recent and oldest events"() {
		new File(temporaryFile).write(event1AsCsv + "\n" + event2AsCsv)

		assert storage.mostRecentEventTime == event1.revisionDate
		assert storage.oldestEventTime == event2.revisionDate
	}

	@Test void "should save events with multi-line comments"() {
		storage.appendToEventsFile([eventWithMultiLineComment])
		def events = storage.readAllEvents()

		assert events.size() == 1
		assert events.first() == eventWithMultiLineComment
	}

	@Test void "should read/write events with additional attributes"() {
		storage.appendToEventsFile([eventWithAdditionalAttributes])
		def events = storage.readAllEvents()

		assert events.size() == 1
		assert events.first() == eventWithAdditionalAttributes
	}

	@Test void "should only append events with date before oldest event"() {
		assert storage.appendToEventsFile([event1])
		assert storage.appendToEventsFile([event2])

		assert storage.appendToEventsFile([eventWithDate(event1, "19:37:57 03/02/2007")])
		assert !storage.appendToEventsFile([eventWithDate(event1, "19:37:57 01/03/2013")])
		assert !storage.appendToEventsFile([eventWithDate(event1, "19:37:57 11/04/2013")])
	}

	@Test void "should only prepend events with date after most recent event"() {
		assert storage.prependToEventsFile([event2])
		assert storage.prependToEventsFile([event1])

		assert !storage.prependToEventsFile([eventWithDate(event1, "19:37:57 03/02/2007")])
		assert !storage.prependToEventsFile([eventWithDate(event1, "19:37:57 01/03/2013")])
		assert storage.prependToEventsFile([eventWithDate(event1, "19:37:57 11/04/2013")])
	}

	private static eventWithDate(FileChangeEvent event, String date) {
		def commitInfo = new CommitInfo(event.commitInfo.revision, event.commitInfo.author, exactDateTime(date), event.commitInfo.commitMessage)
		new FileChangeEvent(commitInfo, event.fileChangeInfo)
	}

	private static createTemporaryFile() {
		def temporaryFile = new File("test-events-file.csv").absolutePath
		if (new File(temporaryFile).exists()) {
			new File(temporaryFile).delete()
		}
		temporaryFile
	}

	private final event1 = new FileChangeEvent(
			new CommitInfo("b421d0ebd66701187c10c2b0c7f519dc435531ae", "Tim Perry", exactDateTime("19:37:57 01/04/2013"), "Added support for iterable datapoints"),
			new FileChangeInfo("", "AllMembersSupplier.java", "", "/src/main/java/org/junit/experimental/theories/internal", "MODIFICATION",
					new ChangeStats(178, 204, 23, 3, 0), new ChangeStats(6758, 7807, 878, 304, 0), []
			)
	)
	private final event2 = new FileChangeEvent(
			new CommitInfo("43b0fe352d5bced0c341640d0c630d23f2022a7e", "dsaff <dsaff>", exactDateTime("15:42:16 03/10/2007"), "Rename TestMethod -> JUnit4MethodRunner"),
			new FileChangeInfo("", "Theories.java", "", "/src/org/junit/experimental/theories", "MODIFICATION",
					new ChangeStats(37, 37, 0, 4, 0), new ChangeStats(950, 978, 0, 215, 0), []
			)
	)
	private final event1AsCsv = "2013-04-01 19:37:57 +0000,b421d0ebd66701187c10c2b0c7f519dc435531ae,Tim Perry,,AllMembersSupplier.java,,/src/main/java/org/junit/experimental/theories/internal,MODIFICATION,178,204,23,3,0,6758,7807,878,304,0,Added support for iterable datapoints"
	private final event2AsCsv = "2007-10-03 15:42:16 +0000,43b0fe352d5bced0c341640d0c630d23f2022a7e,dsaff <dsaff>,,Theories.java,,/src/org/junit/experimental/theories,MODIFICATION,37,37,0,4,0,950,978,0,215,0,Rename TestMethod -> JUnit4MethodRunner"

	private final eventWithMultiLineComment = new FileChangeEvent(
			new CommitInfo("12345", "me", exactDateTime("15:42:16 03/10/2007"), "This\nis\na multi-line\ncommit message"),
			new FileChangeInfo("", "Some.java", "", "/src/somewhere", "MODIFICATION",
					new ChangeStats(37, 37, 0, 4, 0), new ChangeStats(950, 978, 0, 215, 0), []
			)
	)

	private final eventWithAdditionalAttributes = new FileChangeEvent(
			new CommitInfo("12345", "me", exactDateTime("15:42:16 03/10/2007"), "commit message"),
			new FileChangeInfo("", "Some.java", "", "/src/somewhere", "MODIFICATION",
					new ChangeStats(37, 37, 0, 4, 0), new ChangeStats(950, 978, 0, 215, 0),
					["attribute1", "attribute2"]
			)
	)

	EventStorageTest() {
		temporaryFile = createTemporaryFile()
		storage = new EventStorage(temporaryFile, TimeZone.getTimeZone("UTC"))
	}

	private final String temporaryFile
	private final EventStorage storage

}
