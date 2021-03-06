package org.embulk.standards;

import java.util.List;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import javax.validation.constraints.NotNull;
import com.google.common.collect.ImmutableList;
import com.google.common.base.Optional;
import com.fasterxml.jackson.annotation.JacksonInject;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.config.ConfigSource;
import org.embulk.config.ConfigDiff;
import org.embulk.config.CommitReport;
import org.embulk.spi.BufferAllocator;
import org.embulk.spi.Exec;
import org.embulk.spi.FileInputPlugin;
import org.embulk.spi.TransactionalFileInput;
import org.embulk.spi.util.InputStreamFileInput;
import org.slf4j.Logger;

public class LocalFileInputPlugin
        implements FileInputPlugin
{
    public interface PluginTask
            extends Task
    {
        @Config("path_prefix")
        public String getPathPrefix();

        @Config("last_path")
        @ConfigDefault("null")
        public Optional<String> getLastPath();

        public List<String> getFiles();
        public void setFiles(List<String> files);

        @JacksonInject
        public BufferAllocator getBufferAllocator();
    }

    private final Logger log = Exec.getLogger(getClass());

    @Override
    public ConfigDiff transaction(ConfigSource config, FileInputPlugin.Control control)
    {
        PluginTask task = config.loadConfig(PluginTask.class);

        // list files recursively
        List<String> files = listFiles(task);
        log.info("Loading files {}", files);
        task.setFiles(files);

        // number of processors is same with number of files
        int processorCount = task.getFiles().size();
        return resume(task.dump(), processorCount, control);
    }

    @Override
    public ConfigDiff resume(TaskSource taskSource,
            int processorCount,
            FileInputPlugin.Control control)
    {
        control.run(taskSource, processorCount);
        return Exec.newConfigDiff();
    }

    @Override
    public void cleanup(TaskSource taskSource,
            int processorCount,
            List<CommitReport> successCommitReports)
    { }

    public List<String> listFiles(PluginTask task)
    {
        Path pathPrefix = Paths.get(task.getPathPrefix()).normalize();
        final Path directory;
        final String fileNamePrefix;
        if (Files.isDirectory(pathPrefix)) {
            directory = pathPrefix;
            fileNamePrefix = "";
        } else {
            fileNamePrefix = pathPrefix.getFileName().toString();
            Path d = pathPrefix.getParent();
            directory = (d == null ? Paths.get(".") : d);
        }

        final ImmutableList.Builder<String> builder = ImmutableList.builder();
        final String lastPath = task.getLastPath().orNull();
        try {
            log.info("Listing local files at directory '{}' filtering filename by prefix '{}'", directory, fileNamePrefix);
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes aAttrs)
                {
                    if (lastPath == null || path.toString().compareTo(lastPath) > 0) {
                        if (path.getParent().equals(directory)) {
                            if (path.getFileName().toString().startsWith(fileNamePrefix)) {
                                builder.add(path.toString());
                            }
                        } else {
                            builder.add(path.toString());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw new RuntimeException(String.format("Failed get a list of local files at '%s'", directory), ex);
        }
        return builder.build();
    }

    @Override
    public TransactionalFileInput open(TaskSource taskSource, int processorIndex)
    {
        PluginTask task = taskSource.loadTask(PluginTask.class);
        return new LocalFileInput(task, processorIndex);
    }

    public static class LocalFileInput
            extends InputStreamFileInput
            implements TransactionalFileInput
    {
        // TODO create single-file InputStreamFileInput utility
        private static class SingleFileProvider
                implements InputStreamFileInput.Provider
        {
            private final File file;
            private boolean opened = false;

            public SingleFileProvider(File file)
            {
                this.file = file;
            }

            @Override
            public InputStream openNext() throws IOException
            {
                if (opened) {
                    return null;
                }
                opened = true;
                return new FileInputStream(file);
            }

            @Override
            public void close() { }
        }

        public LocalFileInput(PluginTask task, int processorIndex)
        {
            super(task.getBufferAllocator(), new SingleFileProvider(new File(task.getFiles().get(processorIndex))));
        }

        @Override
        public void abort() { }

        @Override
        public CommitReport commit()
        {
            return Exec.newCommitReport();
        }
    }
}
