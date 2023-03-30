/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.tcm.listeners;

import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.gms.ApplicationState;
import org.apache.cassandra.gms.Gossiper;
import org.apache.cassandra.schema.DistributedSchema;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.schema.SchemaDiagnostics;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.tcm.ClusterMetadata;

public class SchemaListener implements ChangeListener
{
    // Special instance used during startup to ensure that SSTable files are not opened until
    // replay of the locally persisted metadata log is complete. Failure to do this can result
    // in deserialization errors if an SSTables written with schema at epoch X are opened before
    // the log replay has replayed X. After replay is complete, this instance is replaced with
    // a standard SchemaListener.
    public static final SchemaListener INSTANCE_FOR_STARTUP = new SchemaListener()
    {
        @Override
        public void notifyPreCommit(ClusterMetadata prev, ClusterMetadata next)
        {
            notifyInternal(prev, next, false);
        }
    };

    @Override
    public void notifyPreCommit(ClusterMetadata prev, ClusterMetadata next)
    {
        notifyInternal(prev, next, true);
    }

    protected void notifyInternal(ClusterMetadata prev, ClusterMetadata next, boolean loadSSTables)
    {
        if (!next.schema.lastModified().equals(prev.schema.lastModified()))
            next.schema.initializeKeyspaceInstances(prev.schema, loadSSTables);
    }

    @Override
    public void notifyPostCommit(ClusterMetadata prev, ClusterMetadata next)
    {
        if (!next.schema.lastModified().equals(prev.schema.lastModified()))
        {
            DistributedSchema.maybeRebuildViews(prev.schema, next.schema);
            SchemaDiagnostics.versionUpdated(Schema.instance);
            Gossiper.instance.addLocalApplicationState(ApplicationState.SCHEMA, StorageService.instance.valueFactory.schema(next.schema.getVersion()));
            SystemKeyspace.updateSchemaVersion(next.schema.getVersion());
        }
    }
}