/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http11;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

import javax.net.ssl.SSLEngine;
import javax.servlet.http.HttpUpgradeHandler;

import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.Processor;
import org.apache.coyote.http11.upgrade.UpgradeProcessor;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;
import org.apache.tomcat.util.net.NioEndpoint.Handler;
import org.apache.tomcat.util.net.SSLImplementation;
import org.apache.tomcat.util.net.SecureNioChannel;
import org.apache.tomcat.util.net.SocketStatus;
import org.apache.tomcat.util.net.SocketWrapperBase;


/**
 * Abstract the protocol implementation, including threading, etc.
 * Processor is single threaded and specific to stream-based protocols,
 * will not fit Jk protocols like JNI.
 *
 * @author Remy Maucherat
 * @author Costin Manolache
 */
public class Http11NioProtocol extends AbstractHttp11JsseProtocol<NioChannel> {

    private static final Log log = LogFactory.getLog(Http11NioProtocol.class);


    public Http11NioProtocol() {
        super(new NioEndpoint());
        Http11ConnectionHandler cHandler = new Http11ConnectionHandler(this);
        setHandler(cHandler);
        ((NioEndpoint) getEndpoint()).setHandler(cHandler);
    }


    @Override
    protected Log getLog() { return log; }


    @Override
    public void start() throws Exception {
        super.start();
        if (npnHandler != null) {
            npnHandler.init(getEndpoint(), 0, getAdapter());
        }
    }


    // -------------------- Pool setup --------------------

    public void setPollerThreadCount(int count) {
        ((NioEndpoint)getEndpoint()).setPollerThreadCount(count);
    }

    public int getPollerThreadCount() {
        return ((NioEndpoint)getEndpoint()).getPollerThreadCount();
    }

    public void setSelectorTimeout(long timeout) {
        ((NioEndpoint)getEndpoint()).setSelectorTimeout(timeout);
    }

    public long getSelectorTimeout() {
        return ((NioEndpoint)getEndpoint()).getSelectorTimeout();
    }

    public void setAcceptorThreadPriority(int threadPriority) {
        ((NioEndpoint)getEndpoint()).setAcceptorThreadPriority(threadPriority);
    }

    public void setPollerThreadPriority(int threadPriority) {
        ((NioEndpoint)getEndpoint()).setPollerThreadPriority(threadPriority);
    }

    public int getAcceptorThreadPriority() {
      return ((NioEndpoint)getEndpoint()).getAcceptorThreadPriority();
    }

    public int getPollerThreadPriority() {
      return ((NioEndpoint)getEndpoint()).getThreadPriority();
    }


    // -------------------- Tcp setup --------------------
    public void setOomParachute(int oomParachute) {
        ((NioEndpoint)getEndpoint()).setOomParachute(oomParachute);
    }

    // ----------------------------------------------------- JMX related methods

    @Override
    protected String getNamePrefix() {
        if (isSSLEnabled()) {
            return ("https-nio");
        } else {
            return ("http-nio");
        }
    }


    // --------------------  Connection handler --------------------

    protected static class Http11ConnectionHandler
            extends AbstractConnectionHandler<NioChannel,Http11NioProcessor>
            implements Handler {

        protected Http11NioProtocol proto;

        Http11ConnectionHandler(Http11NioProtocol proto) {
            this.proto = proto;
        }

        @Override
        protected AbstractProtocol<NioChannel> getProtocol() {
            return proto;
        }

        @Override
        protected Log getLog() {
            return log;
        }


        @Override
        public SSLImplementation getSslImplementation() {
            return proto.sslImplementation;
        }

        /**
         * Expected to be used by the Poller to release resources on socket
         * close, errors etc.
         */
        @Override
        public void release(SocketChannel socket) {
            if (log.isDebugEnabled())
                log.debug("Iterating through our connections to release a socket channel:"+socket);
            boolean released = false;
            Iterator<java.util.Map.Entry<NioChannel, Processor<NioChannel>>> it = connections.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<NioChannel, Processor<NioChannel>> entry = it.next();
                if (entry.getKey().getIOChannel()==socket) {
                    it.remove();
                    Processor<NioChannel> result = entry.getValue();
                    result.recycle();
                    unregister(result);
                    released = true;
                    break;
                }
            }
            if (log.isDebugEnabled())
                log.debug("Done iterating through our connections to release a socket channel:"+socket +" released:"+released);
        }

        /**
         * Expected to be used by the Poller to release resources on socket
         * close, errors etc.
         */
        @Override
        public void release(SocketWrapperBase<NioChannel> socket) {
            Processor<NioChannel> processor =
                connections.remove(socket.getSocket());
            if (processor != null) {
                processor.recycle();
                recycledProcessors.push(processor);
            }
        }

        @Override
        public SocketState process(SocketWrapperBase<NioChannel> socket,
                SocketStatus status) {
            if (proto.npnHandler != null) {
                SocketState ss = proto.npnHandler.process(socket, status);
                if (ss != SocketState.OPEN) {
                    return ss;
                }
            }
            return super.process(socket, status);
        }


        @Override
        public void release(SocketWrapperBase<NioChannel> socket,
                Processor<NioChannel> processor, boolean addToPoller) {
            processor.recycle();
            recycledProcessors.push(processor);
            if (addToPoller) {
                socket.registerReadInterest();
            }
        }


        @Override
        protected void initSsl(SocketWrapperBase<NioChannel> socket,
                Processor<NioChannel> processor) {
            if (proto.isSSLEnabled() &&
                    (proto.sslImplementation != null)
                    && (socket.getSocket() instanceof SecureNioChannel)) {
                SecureNioChannel ch = (SecureNioChannel)socket.getSocket();
                processor.setSslSupport(
                        proto.sslImplementation.getSSLSupport(
                                ch.getSslEngine().getSession()));
            } else {
                processor.setSslSupport(null);
            }

        }

        @Override
        protected void longPoll(SocketWrapperBase<NioChannel> socket,
                Processor<NioChannel> processor) {

            if (processor.isAsync()) {
                socket.setAsync(true);
            } else {
                // Either:
                //  - this is an upgraded connection
                //  - the request line/headers have not been completely
                //    read
                socket.registerReadInterest();
            }
        }

        @Override
        public Http11NioProcessor createProcessor() {
            Http11NioProcessor processor = new Http11NioProcessor(
                    proto.getMaxHttpHeaderSize(), proto.getEndpoint(),
                    proto.getMaxTrailerSize(), proto.getMaxExtensionSize(),
                    proto.getMaxSwallowSize());
            proto.configureProcessor(processor);
            register(processor);
            return processor;
        }

        @Override
        protected Processor<NioChannel> createUpgradeProcessor(
                SocketWrapperBase<NioChannel> socket, ByteBuffer leftoverInput,
                HttpUpgradeHandler httpUpgradeHandler)
                throws IOException {
            return new UpgradeProcessor<>(socket, leftoverInput, httpUpgradeHandler);
        }

        @Override
        public void onCreateSSLEngine(SSLEngine engine) {
            if (proto.npnHandler != null) {
                proto.npnHandler.onCreateEngine(engine);
            }
        }
    }
}
