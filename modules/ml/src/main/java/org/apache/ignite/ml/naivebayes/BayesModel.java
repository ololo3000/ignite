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

package org.apache.ignite.ml.naivebayes;

import org.apache.ignite.ml.Exportable;
import org.apache.ignite.ml.IgniteModel;

/**
 * Interface for Bayes Models.
 */
public interface BayesModel<MODEL extends BayesModel, FEATURES, OUTPUT>
        extends IgniteModel<FEATURES, OUTPUT>, Exportable<MODEL> {
    /**
     * Returns an array where the index correapons a label, and value corresponds {@code log(probalility)} to be this label.
     * The prior probabilities are not count.
     */
    public double[] probabilityPowers(FEATURES vector);
}
