/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.redis.replicator;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.apache.rocketmq.redis.replicator.cmd.Command;
import org.apache.rocketmq.redis.replicator.cmd.CommandName;
import org.apache.rocketmq.redis.replicator.cmd.CommandParser;
import org.apache.rocketmq.redis.replicator.cmd.ReplyParser;
import org.apache.rocketmq.redis.replicator.io.RedisInputStream;
import org.apache.rocketmq.redis.replicator.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.rocketmq.redis.replicator.Status.CONNECTED;
import static org.apache.rocketmq.redis.replicator.Status.DISCONNECTED;

public class RedisAofReplicator extends AbstractReplicator {

    protected static final Logger LOGGER = LoggerFactory.getLogger(RedisAofReplicator.class);
    protected final ReplyParser replyParser;

    public RedisAofReplicator(File file, Configuration configuration) throws FileNotFoundException {
        this(new FileInputStream(file), configuration);
    }

    public RedisAofReplicator(InputStream in, Configuration configuration) {
        Objects.requireNonNull(in);
        Objects.requireNonNull(configuration);
        this.configuration = configuration;
        this.inputStream = new RedisInputStream(in, this.configuration.getBufferSize());
        this.inputStream.setRawByteListeners(this.rawByteListeners);
        this.replyParser = new ReplyParser(inputStream);
        builtInCommandParserRegister();
        if (configuration.isUseDefaultExceptionListener())
            addExceptionListener(new DefaultExceptionListener());
    }

    @Override
    public void open() throws IOException {
        if (!this.connected.compareAndSet(DISCONNECTED, CONNECTED)) return;
        try {
            doOpen();
        } catch (EOFException ignore) {
        } catch (UncheckedIOException e) {
            if (!(e.getCause() instanceof EOFException))
                throw e.getCause();
        } finally {
            doClose();
            doCloseListener(this);
        }
    }

    protected void doOpen() throws IOException {
        while (getStatus() == CONNECTED) {
            Object obj = replyParser.parse();

            if (obj instanceof Object[]) {
                if (verbose()) LOGGER.info(Arrays.deepToString((Object[]) obj));
                Object[] command = (Object[]) obj;
                CommandName name = CommandName.name(new String((byte[]) command[0], UTF_8));
                final CommandParser<? extends Command> parser;
                if ((parser = commands.get(name)) == null) {
                    LOGGER.warn("command [" + name + "] not register. raw command:[" + Arrays.deepToString(command) + "]");
                    continue;
                }
                submitEvent(parser.parse(command));
            } else {
                LOGGER.info("redis reply:" + obj);
            }
        }
    }
}