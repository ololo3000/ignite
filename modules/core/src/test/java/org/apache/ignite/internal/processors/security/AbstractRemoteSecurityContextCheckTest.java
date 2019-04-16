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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.cache.Cache;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.MutableEntry;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCompute;
import org.apache.ignite.Ignition;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.lang.IgniteBiInClosure;
import org.apache.ignite.lang.IgniteBiPredicate;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.lang.IgniteClosure;
import org.apache.ignite.lang.IgniteRunnable;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

/**
 *
 */
public abstract class AbstractRemoteSecurityContextCheckTest extends AbstractSecurityTest {
    /** Transition load cache. */
    protected static final String TRANSITION_LOAD_CACHE = "TRANSITION_LOAD_CACHE";

    /** Name of server initiator node. */
    protected static final String SRV_INITIATOR = "srv_initiator";

    /** Name of client initiator node. */
    protected static final String CLNT_INITIATOR = "clnt_initiator";

    /** Name of server feature call node. */
    protected static final String SRV_RUN = "srv_run";

    /** Name of client feature call node. */
    protected static final String CLNT_RUN = "clnt_run";

    /** Name of server feature transit node. */
    protected static final String SRV_CHECK = "srv_check";

    /** Name of client feature transit node. */
    protected static final String CLNT_CHECK = "clnt_check";

    /** Name of server endpoint node. */
    protected static final String SRV_ENDPOINT = "srv_endpoint";

    /** Name of client endpoint node. */
    protected static final String CLNT_ENDPOINT = "clnt_endpoint";

    /** Verifier to check results of tests. */
    private static final Verifier VERIFIER = new Verifier();

    /**
     * Registers current security context and increments invoke's counter.
     */
    protected static void register() {
        VERIFIER.register((IgniteEx)Ignition.localIgnite());
    }

    /**
     * @return IgniteCompute is produced by passed node for cluster group that contains nodes with ids from collection.
     */
    protected static IgniteCompute compute(Ignite ignite, Collection<UUID> ids) {
        return ignite.compute(ignite.cluster().forNodeIds(ids));
    }

    /**
     * @return IgniteCompute is produced by passed node for cluster group that contains node with id.
     */
    protected static IgniteCompute compute(Ignite ignite, UUID id) {
        return ignite.compute(ignite.cluster().forNodeId(id));
    }

    /**
     * @param ign Node.
     * @return Security subject id of passed node.
     */
    protected static UUID secSubjectId(IgniteEx ign) {
        return ign.context().security().securityContext().subject().id();
    }

    /**
     * @param name Security subject id of passed node.
     * @return Security subject id of passed node.
     */
    protected UUID secSubjectId(String name) {
        return secSubjectId(grid(name));
    }

    /**
     * @return Collection of feature call nodes ids.
     */
    protected Collection<UUID> nodesToRun() {
        return Arrays.asList(nodeId(SRV_RUN), nodeId(CLNT_RUN));
    }

    /**
     * @return Collection of feature transit nodes ids.
     */
    protected Collection<UUID> nodesToCheck() {
        return Arrays.asList(nodeId(SRV_CHECK), nodeId(CLNT_CHECK));
    }

    /**
     * @return Collection of endpont nodes ids.
     */
    protected Collection<UUID> endpoints() {
        return Arrays.asList(nodeId(SRV_ENDPOINT), nodeId(CLNT_ENDPOINT));
    }

    /**
     * @param name Node name.
     * @return Node id.
     */
    protected UUID nodeId(String name) {
        return grid(name).context().discovery().localNode().id();
    }

    /**
     * Setups expected behavior to passed verifier.
     */
    protected abstract void setupVerifier(Verifier verifier);

    /**
     * @param initiator Node that initiates an execution.
     * @param op Operation.
     */
    protected void runAndCheck(IgniteEx initiator, IgniteRunnable op) {
        runAndCheck(initiator, Stream.of(op));
    }

    /**
     * Sets up VERIFIER, performs the runnable and checks the result.
     *
     * @param initiator Node that initiates an execution.
     * @param ops Operations.
     */
    protected void runAndCheck(IgniteEx initiator, Stream<IgniteRunnable> ops) {
        ops.forEach(r -> {
            setupVerifier(VERIFIER.start(secSubjectId(initiator)));

            compute(initiator, nodesToRun()).broadcast(r);

            VERIFIER.checkResult();
        });
    }

    /**
     * Responsible for verifying of tests results.
     */
    public static class Verifier {
        /**
         * Map that contains an expected behaviour.
         */
        private final ConcurrentHashMap<String, T2<Integer, Integer>> map = new ConcurrentHashMap<>();

        /**
         * List of registered security subjects.
         */
        private final List<T2<UUID, String>> list = Collections.synchronizedList(new ArrayList<>());

        /**
         * Expected security subject id.
         */
        private UUID expSecSubjId;

        /**
         * Prepare for test.
         *
         * @param expSecSubjId Expected security subject id.
         */
        private Verifier start(UUID expSecSubjId) {
            this.expSecSubjId = expSecSubjId;

            list.clear();
            map.clear();

            return this;
        }

        /**
         * Adds expected behaivior the method {@link #register(IgniteEx)} will be invoke exp times on the node with
         * passed name.
         *
         * @param nodeName Node name.
         * @param num Expected number of invokes.
         */
        public Verifier expect(String nodeName, int num) {
            map.put(nodeName, new T2<>(num, 0));

            return this;
        }

        /**
         * Registers current security context and increments invoke's counter.
         *
         * @param ignite Local node.
         */
        private void register(IgniteEx ignite) {
            assert expSecSubjId != null;
            assert ignite != null;

            list.add(new T2<>(secSubjectId(ignite), ignite.name()));

            map.computeIfPresent(ignite.name(), (name, t2) -> {
                Integer val = t2.getValue();

                t2.setValue(++val);

                return t2;
            });
        }

        /**
         * Checks result of test and clears expected behavior.
         */
        private void checkResult() {
            assert !map.isEmpty();

            list.forEach(t ->
                assertThat("Invalide security context on node " + t.get2(),
                    t.get1(), is(expSecSubjId))
            );

            map.forEach((key, value) ->
                assertThat("Node " + key + ". Execution of register: ",
                    value.get2(), is(value.get1())));

            list.clear();
            map.clear();

            expSecSubjId = null;
        }
    }

    /** */
    protected static class ExecRegisterAndForwardAdapter<K, V> implements IgniteBiInClosure<K, V> {
        /** RegisterExecAndForward. */
        private RegisterExecAndForward<K, V> instance;

        /**
         * @param runnable Runnable.
         */
        public ExecRegisterAndForwardAdapter(IgniteRunnable runnable) {
            instance = new RegisterExecAndForward<>(runnable);
        }

        /**
         * @param node Expected local node name.
         * @param endpoints Collection of endpont nodes ids.
         */
        public ExecRegisterAndForwardAdapter(String node, Collection<UUID> endpoints) {
            instance = new RegisterExecAndForward<>(node, endpoints);
        }

        /**
         * @param endpoints Collection of endpont nodes ids.
         */
        public ExecRegisterAndForwardAdapter(Collection<UUID> endpoints) {
            instance = new RegisterExecAndForward<>(endpoints);
        }

        /** {@inheritDoc} */
        @Override public void apply(K k, V v) {
            instance.run();
        }
    }

    /** */
    protected <K, V>RegisterExecAndForward<K, V> createRunner(String srvName) {
        return new RegisterExecAndForward<>(srvName, endpoints());
    }

    /** */
    protected <K, V>RegisterExecAndForward<K, V> createRunner() {
        return new RegisterExecAndForward<>(endpoints());
    }

    /** */
    protected static class RegisterExecAndForward<K, V> implements IgniteBiPredicate<K, V>, IgniteRunnable,
            IgniteCallable<V>, EntryProcessor<K, V, Object>, IgniteClosure<K, V> {
        /** Runnable. */
        private final IgniteRunnable runnable;

        /** Expected local node name. */
        private final String node;

        /** Collection of endpoint node ids. */
        private final Collection<UUID> endpoints;

        /**
         * @param runnable Runnable.
         */
        public RegisterExecAndForward(IgniteRunnable runnable) {
            this.runnable = Objects.requireNonNull(runnable);
            node = null;
            endpoints = Collections.emptyList();
        }

        /**
         * @param node Expected local node name.
         * @param endpoints Collection of endpont nodes ids.
         */
        public RegisterExecAndForward(String node, Collection<UUID> endpoints) {
            this.node = node;
            this.endpoints = endpoints;
            runnable = null;
        }

        /**
         * @param endpoints Collection of endpont nodes ids.
         */
        public RegisterExecAndForward(Collection<UUID> endpoints) {
            this.endpoints = endpoints;
            runnable = null;
            node = null;
        }

        /** {@inheritDoc} */
        @Override public boolean apply(K k, V v) {
            run();

            return false;
        }

        /** {@inheritDoc} */
        @Override public void run() {
            Ignite loc = Ignition.localIgnite();

            if (node == null || node.equals(loc.name())) {
                register();

                Ignite ignite = Ignition.localIgnite();

                if (runnable != null)
                    runnable.run();
                else {
                    compute(ignite, endpoints)
                        .broadcast(() -> register());
                }
            }
        }

        /** {@inheritDoc} */
        @Override public Object process(MutableEntry<K, V> mutableEntry, Object... objects) {
            run();

            return null;
        }

        /** {@inheritDoc} */
        @Override public V apply(K k) {
            run();

            if (k instanceof Cache.Entry)
                return (V) ((Cache.Entry)k).getValue();

            return null;
        }

        /** {@inheritDoc} */
        @Override public V call() {
            run();

            return null;
        }
    }
}
