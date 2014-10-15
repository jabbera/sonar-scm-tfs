/*
 * SonarQube :: Plugins :: TFS
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
import org.sonar.api.BatchComponent;
import org.sonar.api.batch.InstantiationStrategy;
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
import java.util.List;

@InstantiationStrategy(InstantiationStrategy.PER_BATCH)
public class TfsBlameCommand implements BlameCommand, BatchComponent {

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
  public void blame(FileSystem fs, Iterable<InputFile> files, BlameResult result) {
    File tfsExe = extractTfsAnnotate();
    LOG.debug("Working directory: " + fs.baseDir().getAbsolutePath());
    for (InputFile inputFile : files) {
      String filename = inputFile.relativePath();
      Command cl = createCommandLine(tfsExe, fs.baseDir(), filename);
      TfsBlameConsumer consumer = new TfsBlameConsumer(filename);
      StringStreamConsumer stderr = new StringStreamConsumer();

      int exitCode = execute(cl, consumer, stderr);
      if (exitCode != 0) {
        throw new IllegalStateException("The TFS blame command [" + cl.toString() + "] failed: " + stderr.getOutput());
      }
      List<BlameLine> lines = consumer.getLines();
      result.add(inputFile, lines);
    }
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
    if (workingDirectory != null) {
      cl.setDirectory(workingDirectory);
    }
    cl.addArgument(filename);
    return cl;
  }

}
