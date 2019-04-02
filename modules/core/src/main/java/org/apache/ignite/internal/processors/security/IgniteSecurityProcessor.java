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

package org.apache.ignite.internal.processors.security;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.processors.GridProcessor;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.marshaller.MarshallerUtils;
import org.apache.ignite.marshaller.jdk.JdkMarshaller;
import org.apache.ignite.plugin.security.AuthenticationContext;
import org.apache.ignite.plugin.security.SecurityCredentials;
import org.apache.ignite.plugin.security.SecurityException;
import org.apache.ignite.plugin.security.SecurityPermission;
import org.apache.ignite.plugin.security.SecuritySubject;
import org.apache.ignite.spi.IgniteNodeValidationResult;
import org.apache.ignite.spi.discovery.DiscoveryDataBag;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.events.EventType.EVT_NODE_FAILED;
import static org.apache.ignite.events.EventType.EVT_NODE_LEFT;
import static org.apache.ignite.internal.processors.security.SecurityLogMarker.AUTHENTICATION;
import static org.apache.ignite.internal.processors.security.SecurityLogMarker.AUTHORIZATION;
import static org.apache.ignite.internal.processors.security.SecurityUtils.nodeSecurityContext;

/**
 * Default Grid security Manager implementation.
 */
public class IgniteSecurityProcessor implements IgniteSecurity, GridProcessor {
    /** */
    private static final String MSG_SEC_PROC_CLS_IS_INVALID = "Local node's grid security processor class " +
        "is not equal to remote node's grid security processor class " +
        "[locNodeId=%s, rmtNodeId=%s, locCls=%s, rmtCls=%s]";

    /** Internal attribute name constant. */
    public static final String ATTR_GRID_SEC_PROC_CLASS = "grid.security.processor.class";

    /** Current security context. */
    private final ThreadLocal<SecurityContext> curSecCtx = ThreadLocal.withInitial(this::localSecurityContext);

    /** Grid kernal context. */
    private final GridKernalContext ctx;

    /** Security processor. */
    private final GridSecurityProcessor secPrc;

    /** Must use JDK marshaller for Security Subject. */
    private final JdkMarshaller marsh;

    /** Map of security contexts. Key is node's id. */
    private final Map<UUID, SecurityContext> secCtxs = new ConcurrentHashMap<>();

    /** Grid logger. */
    private IgniteLogger log;

    /**
     * @param ctx Grid kernal context.
     * @param secPrc Security processor.
     */
    public IgniteSecurityProcessor(GridKernalContext ctx, GridSecurityProcessor secPrc) {
        assert ctx != null;
        assert secPrc != null;

        this.ctx = ctx;
        this.secPrc = secPrc;

        log = ctx.log(getClass());

        marsh = MarshallerUtils.jdkMarshaller(ctx.igniteInstanceName());
    }

    /** {@inheritDoc} */
    @Override public OperationSecurityContext withContext(SecurityContext secCtx) {
        assert secCtx != null;

        SecurityContext old = curSecCtx.get();

        curSecCtx.set(secCtx);

        return new OperationSecurityContext(this, old);
    }

    /** {@inheritDoc} */
    @Override public OperationSecurityContext withContext(UUID nodeId) {
        return withContext(
            secCtxs.computeIfAbsent(nodeId,
                uuid -> nodeSecurityContext(
                    marsh, U.resolveClassLoader(ctx.config()), ctx.discovery().node(uuid)
                )
            )
        );
    }

    /** {@inheritDoc} */
    @Override public SecurityContext securityContext() {
        SecurityContext res = curSecCtx.get();

        assert res != null;

        return res;
    }

    /** {@inheritDoc} */
    @Override public SecurityContext authenticateNode(ClusterNode node, SecurityCredentials cred)
        throws IgniteCheckedException {
        SecurityContext securityCtx = secPrc.authenticateNode(node, cred);

        logNodeAuthentication(node, securityCtx);

        return securityCtx;
    }

    /** {@inheritDoc} */
    @Override public boolean isGlobalNodeAuthentication() {
        return secPrc.isGlobalNodeAuthentication();
    }

    /** {@inheritDoc} */
    @Override public SecurityContext authenticate(AuthenticationContext ctx) throws IgniteCheckedException {
        SecurityContext securityCtx = secPrc.authenticate(ctx);

        logAuthentication(ctx, securityCtx);

        return securityCtx;
    }

    /** {@inheritDoc} */
    @Override public Collection<SecuritySubject> authenticatedSubjects() throws IgniteCheckedException {
        return secPrc.authenticatedSubjects();
    }

    /** {@inheritDoc} */
    @Override public SecuritySubject authenticatedSubject(UUID subjId) throws IgniteCheckedException {
        return secPrc.authenticatedSubject(subjId);
    }

    /** {@inheritDoc} */
    @Override public void onSessionExpired(UUID subjId) {
        secPrc.onSessionExpired(subjId);
    }

    /** {@inheritDoc} */
    @Override public void authorize(String name, SecurityPermission perm) throws SecurityException {
        SecurityContext secCtx = curSecCtx.get();

        SecurityException authorizationErr = null;

        assert secCtx != null;

        try {
            secPrc.authorize(name, perm, secCtx);
        }
        catch (SecurityException e) {
            authorizationErr = e;
        }

        logAuthorization(name, perm, authorizationErr);

        if (authorizationErr != null)
            throw authorizationErr;
    }

    /** {@inheritDoc} */
    @Override public boolean enabled() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteCheckedException {
        ctx.addNodeAttribute(ATTR_GRID_SEC_PROC_CLASS, secPrc.getClass().getName());

        secPrc.start();
    }

    /** {@inheritDoc} */
    @Override public void stop(boolean cancel) throws IgniteCheckedException {
        secPrc.stop(cancel);
    }

    /** {@inheritDoc} */
    @Override public void onKernalStart(boolean active) throws IgniteCheckedException {
        ctx.event().addDiscoveryEventListener(
            (evt, discoCache) -> secCtxs.remove(evt.eventNode().id()), EVT_NODE_FAILED, EVT_NODE_LEFT
        );

        secPrc.onKernalStart(active);
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop(boolean cancel) {
        secPrc.onKernalStop(cancel);
    }

    /** {@inheritDoc} */
    @Override public void collectJoiningNodeData(DiscoveryDataBag dataBag) {
        secPrc.collectJoiningNodeData(dataBag);
    }

    /** {@inheritDoc} */
    @Override public void collectGridNodeData(DiscoveryDataBag dataBag) {
        secPrc.collectGridNodeData(dataBag);
    }

    /** {@inheritDoc} */
    @Override public void onGridDataReceived(DiscoveryDataBag.GridDiscoveryData data) {
        secPrc.onGridDataReceived(data);
    }

    /** {@inheritDoc} */
    @Override public void onJoiningNodeDataReceived(DiscoveryDataBag.JoiningNodeDiscoveryData data) {
        secPrc.onJoiningNodeDataReceived(data);
    }

    /** {@inheritDoc} */
    @Override public void printMemoryStats() {
        secPrc.printMemoryStats();
    }

    /** {@inheritDoc} */
    @Override public @Nullable IgniteNodeValidationResult validateNode(ClusterNode node) {
        IgniteNodeValidationResult res = validateSecProcClass(node);

        return res != null ? res : secPrc.validateNode(node);
    }

    /** {@inheritDoc} */
    @Override public @Nullable IgniteNodeValidationResult validateNode(ClusterNode node,
        DiscoveryDataBag.JoiningNodeDiscoveryData discoData) {
        IgniteNodeValidationResult res = validateSecProcClass(node);

        return res != null ? res : secPrc.validateNode(node, discoData);
    }

    /** {@inheritDoc} */
    @Override public @Nullable DiscoveryDataExchangeType discoveryDataType() {
        return secPrc.discoveryDataType();
    }

    /** {@inheritDoc} */
    @Override public void onDisconnected(IgniteFuture<?> reconnectFut) throws IgniteCheckedException {
        secPrc.onDisconnected(reconnectFut);
    }

    /** {@inheritDoc} */
    @Override public @Nullable IgniteInternalFuture<?> onReconnected(
        boolean clusterRestarted) throws IgniteCheckedException {
        return secPrc.onReconnected(clusterRestarted);
    }

    /**
     * Getting local node's security context.
     *
     * @return Security context of local node.
     */
    private SecurityContext localSecurityContext() {
        return nodeSecurityContext(marsh, U.resolveClassLoader(ctx.config()), ctx.discovery().localNode());
    }

    /**
     * Validates that remote node's grid security processor class is the same as local one.
     *
     * @param node Joining node.
     * @return Validation result or {@code null} in case of success.
     */
    private IgniteNodeValidationResult validateSecProcClass(ClusterNode node) {
        String rmtCls = node.attribute(ATTR_GRID_SEC_PROC_CLASS);
        String locCls = secPrc.getClass().getName();

        if (!F.eq(locCls, rmtCls)) {
            return new IgniteNodeValidationResult(node.id(),
                String.format(MSG_SEC_PROC_CLS_IS_INVALID, ctx.localNodeId(), node.id(), locCls, rmtCls),
                String.format(MSG_SEC_PROC_CLS_IS_INVALID, node.id(), ctx.localNodeId(), rmtCls, locCls));
        }

        return null;
    }

    /**
     * Logs authentication result.
     *
     * @param ctx Authentication context.
     * @param securityCtx Authentication security context.
     */
    private void logAuthentication(AuthenticationContext ctx, SecurityContext securityCtx) {
        String subj = "subject={id=" + ctx.subjectId() +
            ", type=" + ctx.subjectType() +
            ", login=" + ctx.credentials().getLogin() + '}';

        if (securityCtx == null)
            log.error(AUTHENTICATION, "Authentication failed [" + subj + ']', null);
        else
            log.info(AUTHENTICATION, "Authentication succeed [" + subj + ']');
    }

    /**
     * Logs node authentication result.
     *
     * @param node Node id to authenticate.
     * @param securityCtx Authentication security context.
     */
    private void logNodeAuthentication(ClusterNode node, SecurityContext securityCtx) {
        if (securityCtx == null)
            log.error(AUTHENTICATION, "Node authentication failed [node=" + node + ']', null);
        else
            log.info(AUTHENTICATION, "Node authentication succeed [node=" + node + ']');
    }

    /**
     * Logs authorization result.
     *
     * @param name Cache name or task class name.
     * @param perm Permission to authorize.
     * @param e Authorization exception.
     */
    private void logAuthorization(String name, SecurityPermission perm, SecurityException e) {
        String authInfo = "perm=" + perm +
            ", name=" + name +
            ", subject=" + curSecCtx.get().subject();

        if (e == null)
            log.info(AUTHORIZATION, "Authorization succeed [" + authInfo + ']');
        else
            log.error(AUTHORIZATION, "Authorization failed [" + authInfo + ']', e);
    }
}
