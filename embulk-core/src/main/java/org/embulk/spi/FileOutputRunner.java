package org.embulk.spi;

import java.util.List;
import java.util.ArrayList;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.config.ConfigSource;
import org.embulk.config.ConfigDiff;
import org.embulk.config.CommitReport;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.plugin.PluginType;
import org.embulk.spi.util.Encoders;

public class FileOutputRunner
        implements OutputPlugin
{
    private final FileOutputPlugin fileOutputPlugin;

    public FileOutputRunner(FileOutputPlugin fileOutputPlugin)
    {
        this.fileOutputPlugin = fileOutputPlugin;
    }

    private interface RunnerTask extends Task
    {
        @Config("type")
        public PluginType getType();

        @Config("encoders")
        @ConfigDefault("[]")
        public List<ConfigSource> getEncoderConfigs();

        @Config("formatter")
        public ConfigSource getFormatterConfig();

        public void setFileOutputTaskSource(TaskSource v);
        public TaskSource getFileOutputTaskSource();

        public void setEncoderTaskSources(List<TaskSource> v);
        public List<TaskSource> getEncoderTaskSources();

        public void setFormatterTaskSource(TaskSource v);
        public TaskSource getFormatterTaskSource();
    }

    protected List<EncoderPlugin> newEncoderPlugins(RunnerTask task)
    {
        return Encoders.newEncoderPlugins(Exec.session(), task.getEncoderConfigs());
    }

    protected FormatterPlugin newFormatterPlugin(RunnerTask task)
    {
        return Exec.newPlugin(FormatterPlugin.class, task.getFormatterConfig().get(PluginType.class, "type"));
    }

    @Override
    public ConfigDiff transaction(ConfigSource config,
            final Schema schema, final int processorCount,
            final OutputPlugin.Control control)
    {
        final RunnerTask task = config.loadConfig(RunnerTask.class);
        return fileOutputPlugin.transaction(config, processorCount, new RunnerControl(schema, task, control));
    }

    public ConfigDiff resume(TaskSource taskSource,
            Schema schema, int processorCount,
            final OutputPlugin.Control control)
    {
        final RunnerTask task = taskSource.loadTask(RunnerTask.class);
        return fileOutputPlugin.resume(task.getFileOutputTaskSource(), processorCount, new RunnerControl(schema, task, control));
    }

    private class RunnerControl
            implements FileOutputPlugin.Control
    {
        private final Schema schema;
        private final RunnerTask task;
        private final List<EncoderPlugin> encoderPlugins;
        private final FormatterPlugin formatterPlugin;
        private final OutputPlugin.Control nextControl;

        public RunnerControl(Schema schema, RunnerTask task, OutputPlugin.Control nextControl)
        {
            this.schema = schema;
            this.task = task;
            // create plugins earlier than run() to throw exceptions early
            this.encoderPlugins = newEncoderPlugins(task);
            this.formatterPlugin = newFormatterPlugin(task);
            this.nextControl = nextControl;
        }

        @Override
        public List<CommitReport> run(final TaskSource fileOutputTaskSource)
        {
            final List<CommitReport> commitReports = new ArrayList<CommitReport>();
            Encoders.transaction(encoderPlugins, task.getEncoderConfigs(), new Encoders.Control() {
                public void run(final List<TaskSource> encoderTaskSources)
                {
                    formatterPlugin.transaction(task.getFormatterConfig(), schema, new FormatterPlugin.Control() {
                        public void run(final TaskSource formatterTaskSource)
                        {
                            task.setFileOutputTaskSource(fileOutputTaskSource);
                            task.setEncoderTaskSources(encoderTaskSources);
                            task.setFormatterTaskSource(formatterTaskSource);
                            commitReports.addAll(nextControl.run(task.dump()));
                        }
                    });
                }
            });
            return commitReports;
        }
    }

    public void cleanup(TaskSource taskSource,
            Schema schema, int processorCount,
            List<CommitReport> successCommitReports)
    {
        fileOutputPlugin.cleanup(taskSource, processorCount, successCommitReports);
    }

    @Override
    public TransactionalPageOutput open(TaskSource taskSource, Schema schema, int processorIndex)
    {
        final RunnerTask task = taskSource.loadTask(RunnerTask.class);
        List<EncoderPlugin> encoderPlugins = newEncoderPlugins(task);
        FormatterPlugin formatterPlugin = newFormatterPlugin(task);

        TransactionalFileOutput tran = null;
        FileOutput fileOutput = null;
        PageOutput output = null;
        try {
            fileOutput = tran = fileOutputPlugin.open(task.getFileOutputTaskSource(), processorIndex);

            fileOutput = Encoders.open(encoderPlugins, task.getEncoderTaskSources(), fileOutput);
            output = formatterPlugin.open(task.getFormatterTaskSource(), schema, fileOutput);
            fileOutput = null;

            TransactionalPageOutput ret = new DelegateTransactionalPageOutput(tran, output);
            tran = null;
            output = null;
            return ret;

        } finally {
            if (output != null) {
                output.close();
            }
            if (fileOutput != null) {
                fileOutput.close();
            }
            if (tran != null) {
                tran.abort();
            }
        }
    }

    private static class DelegateTransactionalPageOutput
            implements TransactionalPageOutput
    {
        private final Transactional tran;
        private final PageOutput output;

        public DelegateTransactionalPageOutput(Transactional tran, PageOutput output)
        {
            this.tran = tran;
            this.output = output;
        }

        @Override
        public void add(Page page)
        {
            output.add(page);
        }

        @Override
        public void finish()
        {
            output.finish();
        }

        @Override
        public void close()
        {
            output.close();
        }

        @Override
        public void abort()
        {
            tran.abort();
        }

        @Override
        public CommitReport commit()
        {
            // TODO check finished
            return tran.commit();
        }
    }
}
