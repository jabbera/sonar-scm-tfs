/*
 * SonarQube :: Plugins :: SCM :: TFS
 * Copyright (C) 2009 ${owner}
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.scm.tfs;

import com.google.common.io.Files;
import com.google.common.io.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.BlameLine;
import org.sonar.api.utils.TempFolder;
import org.sonar.api.utils.command.Command;
import org.sonar.api.utils.command.CommandExecutor;
import org.sonar.api.utils.command.StreamConsumer;
import org.sonar.api.utils.command.StringStreamConsumer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class TfsBlameCommand extends BlameCommand {

  private static final Logger LOG = LoggerFactory.getLogger(TfsBlameCommand.class);
  private final CommandExecutor commandExecutor;
  private TempFolder temp;

  public TfsBlameCommand(TempFolder temp) {
    this(CommandExecutor.create(), temp);
  }

  TfsBlameCommand(CommandExecutor commandExecutor, TempFolder temp) {
    this.commandExecutor = commandExecutor;
    this.temp = temp;
  }

  @Override
  public void blame(BlameInput input, BlameOutput output) {
    File tfsExe = extractTfsAnnotate();
    FileSystem fs = input.fileSystem();
    LOG.debug("Working directory: " + fs.baseDir().getAbsolutePath());
    ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 1);
    List<Future<Void>> tasks = new ArrayList<Future<Void>>();
    for (InputFile inputFile : input.filesToBlame()) {
      tasks.add(submitTask(tfsExe, fs, output, executorService, inputFile));
    }

    for (Future<Void> task : tasks) {
      try {
        task.get();
      } catch (ExecutionException e) {
        // Unwrap ExecutionException
        throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : new IllegalStateException(e.getCause());
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private Future<Void> submitTask(final File tfsExe, final FileSystem fs, final BlameOutput result, ExecutorService executorService, final InputFile inputFile) {
    return executorService.submit(new Callable<Void>() {
      @Override
      public Void call() {
        blame(tfsExe, fs, inputFile, result);
        return null;
      }

    });
  }

  private void blame(File tfsExe, FileSystem fs, InputFile inputFile, BlameOutput output) {
    String filename = inputFile.relativePath();
    Command cl = createCommandLine(tfsExe, fs.baseDir(), filename);
    TfsBlameConsumer consumer = new TfsBlameConsumer(filename);
    StringStreamConsumer stderr = new StringStreamConsumer();

    int exitCode = execute(cl, consumer, stderr);
    if (exitCode != 0) {
      throw new IllegalStateException("The TFS blame command [" + cl.toString() + "] failed: " + stderr.getOutput());
    }
    List<BlameLine> lines = consumer.getLines();
    if (lines.size() == inputFile.lines() - 1) {
      // SONARPLUGINS-3097 Tfs do not report blame on last empty line
      lines.add(lines.get(lines.size() - 1));
    }
    output.blameResult(inputFile, lines);

  }

  private File extractTfsAnnotate() {
    File tfsExe = temp.newFile("SonarTfsAnnotate", ".exe");
    try {
      Files.write(Resources.toByteArray(this.getClass().getResource("/SonarTfsAnnotate.exe")), tfsExe);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to extract SonarTfsAnnotate.exe", e);
    }
    return tfsExe;
  }

  public int execute(Command cl, StreamConsumer consumer, StreamConsumer stderr) {
    LOG.debug("Executing: " + cl);
    return commandExecutor.execute(cl, consumer, stderr, -1);
  }

  private Command createCommandLine(File tfsExe, File workingDirectory, String filename) {
    Command cl = Command.create(tfsExe.getAbsolutePath());
    cl.setDirectory(workingDirectory);
    cl.addArgument(filename);
    return cl;
  }

}
