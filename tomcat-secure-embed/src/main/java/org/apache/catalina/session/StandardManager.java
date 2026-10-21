/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.session;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Session;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;

/**
 * Standard implementation of the <b>Manager</b> interface.
 * <p>
 * Hardened build: sessions are held in memory only. Session persistence across restarts (which relied on Java
 * deserialization of a file in the work directory) has been removed. {@link #load()} and {@link #unload()} are
 * no-ops and all sessions are expired when the manager stops.
 */
public class StandardManager extends ManagerBase {

    /**
     * Construct a new {@code StandardManager} instance.
     */
    public StandardManager() {
        // NO-OP
    }

    private final Log log = LogFactory.getLog(StandardManager.class); // must not be static

    // ----------------------------------------------------- Instance Variables

    /**
     * The descriptive name of this Manager implementation (for logging).
     */
    protected static final String name = "StandardManager";


    // ------------------------------------------------------------- Properties

    @Override
    public String getName() {
        return name;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * {@inheritDoc}
     * <p>
     * Hardened build: session persistence is not supported. This method only clears the internal data structures.
     */
    @Override
    public void load() {
        sessions.clear();
    }


    /**
     * {@inheritDoc}
     * <p>
     * Hardened build: session persistence is not supported. This method is a NO-OP.
     */
    @Override
    public void unload() {
        // NO-OP
    }


    /**
     * Start this component and implement the requirements of
     * {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error that prevents this component from being
     *                                   used
     */
    @Override
    protected void startInternal() throws LifecycleException {
        super.startInternal();
        load();
        setState(LifecycleState.STARTING);
    }


    /**
     * Stop this component and implement the requirements of
     * {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error that prevents this component from being
     *                                   used
     */
    @Override
    protected void stopInternal() throws LifecycleException {

        if (log.isTraceEnabled()) {
            log.trace("Stopping");
        }

        setState(LifecycleState.STOPPING);

        // Expire all active sessions
        Session[] sessions = findSessions();
        for (Session session : sessions) {
            try {
                if (session.isValid()) {
                    session.expire();
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
            } finally {
                // Measure against memory leaking if references to the session
                // object are kept in a shared field somewhere
                session.recycle();
            }
        }

        // Require a new random number generator if we are restarted
        super.stopInternal();
    }
}
