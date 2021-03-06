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
import java.net.InetAddress;
import java.net.InetSocketAddress;

import javax.net.ssl.SSLEngine;

import org.apache.coyote.ActionCode;
import org.apache.coyote.http11.filters.BufferedInputFilter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.Nio2Channel;
import org.apache.tomcat.util.net.Nio2Endpoint;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SecureNio2Channel;
import org.apache.tomcat.util.net.SocketStatus;


/**
 * Processes HTTP requests.
 */
public class Http11Nio2Processor extends AbstractHttp11Processor<Nio2Channel> {

    private static final Log log = LogFactory.getLog(Http11Nio2Processor.class);
    @Override
    protected Log getLog() {
        return log;
    }


    /**
     * SSL information.
     */
    protected SSLSupport sslSupport;


    // ----------------------------------------------------------- Constructors

    public Http11Nio2Processor(int maxHttpHeaderSize, AbstractEndpoint<Nio2Channel> endpoint,
            int maxTrailerSize, int maxExtensionSize, int maxSwallowSize) {

        super(maxHttpHeaderSize, endpoint, maxTrailerSize, maxExtensionSize, maxSwallowSize);
    }


    // --------------------------------------------------------- Public Methods

    @Override
    public SocketState asyncDispatch(SocketStatus status) {
        SocketState state = super.asyncDispatch(status);
        if (state == SocketState.OPEN && socketWrapper.isReadPending()) {
            // Following async processing, a read is still pending, so
            // keep the processor associated
            return SocketState.LONG;
        } else {
            return state;
        }
    }


    @Override
    protected void resetTimeouts() {
        if (!getErrorState().isError() && socketWrapper != null &&
                asyncStateMachine.isAsyncDispatching()) {

            // Reset the timeout
            if (keepAlive) {
                socketWrapper.setTimeout(endpoint.getKeepAliveTimeout());
            } else {
                socketWrapper.setTimeout(endpoint.getSoTimeout());
            }
        }
    }


    @Override
    protected void setSocketTimeout(int timeout) throws IOException {
        socketWrapper.setTimeout(timeout);
    }


    // ----------------------------------------------------- ActionHook Methods

    /**
     * Send an action to the connector.
     *
     * @param actionCode Type of the action
     * @param param Action parameter
     */
    @Override
    @SuppressWarnings("incomplete-switch") // Other cases are handled by action()
    public void actionInternal(ActionCode actionCode, Object param) {

        switch (actionCode) {
        case REQ_LOCAL_NAME_ATTRIBUTE: {
            if (socketWrapper == null || socketWrapper.getSocket() == null) {
                request.localName().recycle();
            } else {
                if (socketWrapper.getLocalName() == null) {
                    InetAddress inetAddr = null;
                    try {
                        inetAddr = ((InetSocketAddress) socketWrapper.getSocket().getIOChannel().getLocalAddress()).getAddress();
                    } catch (IOException e) {
                        // Ignore
                    }
                    if (inetAddr != null) {
                        socketWrapper.setLocalName(inetAddr.getHostName());
                    }
                }
                request.localName().setString(socketWrapper.getLocalName());
            }
            break;
        }
        case REQ_LOCAL_ADDR_ATTRIBUTE: {
            if (socketWrapper == null || socketWrapper.getSocket() == null) {
                request.localAddr().recycle();
            } else {
                if (socketWrapper.getLocalAddr() == null) {
                    try {
                        socketWrapper.setLocalAddr(
                                ((InetSocketAddress) socketWrapper.getSocket().getIOChannel().getLocalAddress()).getAddress().getHostAddress());
                    } catch (IOException e) {
                        // Ignore
                    }
                }
                request.localAddr().setString(socketWrapper.getLocalAddr());
            }
            break;
        }
        case REQ_REMOTEPORT_ATTRIBUTE: {
            if (socketWrapper == null || socketWrapper.getSocket() == null) {
                request.setRemotePort(0);
            } else {
                if (socketWrapper.getRemotePort() == -1) {
                    try {
                        socketWrapper.setRemotePort(((InetSocketAddress) socketWrapper.getSocket().getIOChannel().getRemoteAddress()).getPort());
                    } catch (IOException e) {
                        // Ignore
                    }
                }
                request.setRemotePort(socketWrapper.getRemotePort());
            }
            break;
        }
        case REQ_LOCALPORT_ATTRIBUTE: {
            if (socketWrapper == null || socketWrapper.getSocket() == null) {
                request.setLocalPort(0);
            } else {
                if (socketWrapper.getLocalPort() == -1) {
                    try {
                        socketWrapper.setLocalPort(((InetSocketAddress) socketWrapper.getSocket().getIOChannel().getLocalAddress()).getPort());
                    } catch (IOException e) {
                        // Ignore
                    }
                }
                request.setLocalPort(socketWrapper.getLocalPort());
            }
            break;
        }
        case REQ_SSL_ATTRIBUTE: {
            try {
                if (sslSupport != null) {
                    Object sslO = sslSupport.getCipherSuite();
                    if (sslO != null) {
                        request.setAttribute
                            (SSLSupport.CIPHER_SUITE_KEY, sslO);
                    }
                    sslO = sslSupport.getPeerCertificateChain(false);
                    if (sslO != null) {
                        request.setAttribute
                            (SSLSupport.CERTIFICATE_KEY, sslO);
                    }
                    sslO = sslSupport.getKeySize();
                    if (sslO != null) {
                        request.setAttribute
                            (SSLSupport.KEY_SIZE_KEY, sslO);
                    }
                    sslO = sslSupport.getSessionId();
                    if (sslO != null) {
                        request.setAttribute
                            (SSLSupport.SESSION_ID_KEY, sslO);
                    }
                    request.setAttribute(SSLSupport.SESSION_MGR, sslSupport);
                }
            } catch (Exception e) {
                log.warn(sm.getString("http11processor.socket.ssl"), e);
            }
            break;
        }
        case REQ_SSL_CERTIFICATE: {
            if (sslSupport != null && socketWrapper.getSocket() != null) {
                /*
                 * Consume and buffer the request body, so that it does not
                 * interfere with the client's handshake messages
                 */
                InputFilter[] inputFilters = getInputBuffer().getFilters();
                ((BufferedInputFilter) inputFilters[Constants.BUFFERED_FILTER])
                    .setLimit(maxSavePostSize);
                getInputBuffer().addActiveFilter
                    (inputFilters[Constants.BUFFERED_FILTER]);
                SecureNio2Channel sslChannel = (SecureNio2Channel) socketWrapper.getSocket();
                SSLEngine engine = sslChannel.getSslEngine();
                if (!engine.getNeedClientAuth()) {
                    // Need to re-negotiate SSL connection
                    engine.setNeedClientAuth(true);
                    try {
                        sslChannel.rehandshake();
                        sslSupport = ((Nio2Endpoint)endpoint).getHandler()
                                .getSslImplementation().getSSLSupport(
                                        engine.getSession());
                    } catch (IOException ioe) {
                        log.warn(sm.getString("http11processor.socket.sslreneg"), ioe);
                    }
                }

                try {
                    // use force=false since re-negotiation is handled above
                    // (and it is a NO-OP for NIO anyway)
                    Object sslO = sslSupport.getPeerCertificateChain(false);
                    if( sslO != null) {
                        request.setAttribute
                            (SSLSupport.CERTIFICATE_KEY, sslO);
                    }
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.ssl"), e);
                }
            }
            break;
        }
        }
    }


    // ------------------------------------------------------ Protected Methods

    /**
     * Set the SSL information for this HTTP connection.
     */
    @Override
    public void setSslSupport(SSLSupport sslSupport) {
        this.sslSupport = sslSupport;
    }
}
