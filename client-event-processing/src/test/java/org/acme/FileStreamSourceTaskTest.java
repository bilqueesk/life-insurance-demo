package org.acme;

import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.apache.kafka.connect.storage.OffsetStorageReader;
import org.easymock.EasyMock;

import org.powermock.api.easymock.PowerMock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.QuarkusTest;

import static org.junit.Assert.assertEquals;

import org.acme.infra. FileStreamSourceTask;
import org.acme.infra.FileStreamSourceConnector;

@QuarkusTest
public class FileStreamSourceTaskTest {

    private static final String TOPIC = "test";

    private File tempFile;
    private Map<String, String> config;
    private OffsetStorageReader offsetStorageReader;
    private SourceTaskContext context;
    private FileStreamSourceTask task;

    private boolean verifyMocks = false;

    @BeforeEach
    public void setup() throws IOException {
        tempFile = File.createTempFile("file-stream-source-task-test", null);
        config = new HashMap<>();
        config.put(FileStreamSourceConnector.FILE_CONFIG, tempFile.getAbsolutePath());
        config.put(FileStreamSourceConnector.TOPIC_CONFIG, TOPIC);
        task = new FileStreamSourceTask();
        offsetStorageReader = PowerMock.createMock(OffsetStorageReader.class);
        context = PowerMock.createMock(SourceTaskContext.class);
        task.initialize(context);
    }

    @AfterEach
    public void teardown() {
        tempFile.delete();

        if (verifyMocks)
            PowerMock.verifyAll();
    }

    private void replay() {
        PowerMock.replayAll();
        verifyMocks = true;
    }

    @Test
    public void testNormalLifecycle() throws InterruptedException, IOException {
        expectOffsetLookupReturnNone();
        replay();

        task.start(config);

        FileOutputStream os = new FileOutputStream(tempFile);
        assertEquals(null, task.poll());
        os.write("partial line".getBytes());
        os.flush();
        System.out.println("BK - After Partial lines Tests");
        assertEquals(null, task.poll());
        os.write(" finished\n".getBytes());
        os.flush();
        List<SourceRecord> records = task.poll();
        assertEquals(1, records.size());
        System.out.println("bk-> Source rEcords" );
        assertEquals(TOPIC, records.get(0).topic());
        assertEquals("partial line finished", records.get(0).value());
        System.out.println("BK - After Partial lines finished" + records.get(0).value());
        assertEquals(Collections.singletonMap(FileStreamSourceTask.FILENAME_FIELD, tempFile.getAbsolutePath()), records.get(0).sourcePartition());
        assertEquals(Collections.singletonMap(FileStreamSourceTask.POSITION_FIELD, 22L), records.get(0).sourceOffset());
        assertEquals(null, task.poll());

        // Different line endings, and make sure the final \r doesn't result in a line until we can
        // read the subsequent byte.
        os.write("line1\rline2\r\nline3\nline4\n\r".getBytes());
        os.flush();
        records = task.poll();
        assertEquals(4, records.size());
        assertEquals("line1", records.get(0).value());
        assertEquals(Collections.singletonMap(FileStreamSourceTask.FILENAME_FIELD, tempFile.getAbsolutePath()), records.get(0).sourcePartition());
        assertEquals(Collections.singletonMap(FileStreamSourceTask.POSITION_FIELD, 28L), records.get(0).sourceOffset());
        assertEquals("line2", records.get(1).value());
        assertEquals(Collections.singletonMap(FileStreamSourceTask.FILENAME_FIELD, tempFile.getAbsolutePath()), records.get(1).sourcePartition());
        assertEquals(Collections.singletonMap(FileStreamSourceTask.POSITION_FIELD, 35L), records.get(1).sourceOffset());
        assertEquals("line3", records.get(2).value());
        assertEquals(Collections.singletonMap(FileStreamSourceTask.FILENAME_FIELD, tempFile.getAbsolutePath()), records.get(2).sourcePartition());
        assertEquals(Collections.singletonMap(FileStreamSourceTask.POSITION_FIELD, 41L), records.get(2).sourceOffset());
        assertEquals("line4", records.get(3).value());
        assertEquals(Collections.singletonMap(FileStreamSourceTask.FILENAME_FIELD, tempFile.getAbsolutePath()), records.get(3).sourcePartition());
        assertEquals(Collections.singletonMap(FileStreamSourceTask.POSITION_FIELD, 47L), records.get(3).sourceOffset());

        os.write("subsequent text".getBytes());
        os.flush();
        records = task.poll();
        assertEquals(1, records.size());
        assertEquals("", records.get(0).value());
        assertEquals(Collections.singletonMap(FileStreamSourceTask.FILENAME_FIELD, tempFile.getAbsolutePath()), records.get(0).sourcePartition());
        assertEquals(Collections.singletonMap(FileStreamSourceTask.POSITION_FIELD, 48L), records.get(0).sourceOffset());

        task.stop();
    }

   //
   // @Test(expected = ConnectException.class)
    public void testMissingTopic() throws InterruptedException {
        replay();

        config.remove(FileStreamSourceConnector.TOPIC_CONFIG);
        task.start(config);
    }

    public void testInvalidFile() throws InterruptedException {
        config.put(FileStreamSourceConnector.FILE_CONFIG, "bogusfilename");
        task.start(config);
        // Currently the task retries indefinitely if the file isn't found, but shouldn't return any data.
        for (int i = 0; i < 100; i++)
            assertEquals(null, task.poll());
    }


    private void expectOffsetLookupReturnNone() {
        EasyMock.expect(context.offsetStorageReader()).andReturn(offsetStorageReader);
        EasyMock.expect(offsetStorageReader.offset(EasyMock.anyObject(Map.class))).andReturn(null);
    }
}