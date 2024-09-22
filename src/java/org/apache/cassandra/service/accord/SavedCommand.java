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

package org.apache.cassandra.service.accord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Function;
import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import accord.api.Result;
import accord.local.Command;
import accord.local.CommonAttributes;
import accord.local.StoreParticipants;
import accord.primitives.Ballot;
import accord.primitives.PartialDeps;
import accord.primitives.PartialTxn;
import accord.primitives.SaveStatus;
import accord.primitives.Status;
import accord.primitives.Timestamp;
import accord.primitives.TxnId;
import accord.primitives.Writes;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputPlus;
import org.apache.cassandra.journal.Journal;
import org.apache.cassandra.service.accord.serializers.CommandSerializers;
import org.apache.cassandra.service.accord.serializers.DepsSerializer;
import org.apache.cassandra.service.accord.serializers.WaitingOnSerializer;
import org.apache.cassandra.utils.Throwables;

import static accord.primitives.Known.KnownDeps.DepsErased;
import static accord.primitives.Known.KnownDeps.DepsUnknown;
import static accord.primitives.Known.KnownDeps.NoDeps;
import static accord.utils.Invariants.illegalState;

public class SavedCommand
{
    // This enum is order-dependent
    public enum Fields
    {
        TXN_ID,
        EXECUTE_AT,
        EXECUTES_AT_LEAST,
        SAVE_STATUS,
        DURABILITY,
        ACCEPTED,
        PROMISED,
        PARTICIPANTS,
        PARTIAL_TXN,
        PARTIAL_DEPS,
        WAITING_ON,
        WRITES,
    }

    // TODO: maybe rename this and enclosing classes?
    public static class DiffWriter implements Journal.Writer
    {
        private final Command before;
        private final Command after;
        private final TxnId txnId;

        // TODO: improve encapsulationd
        @VisibleForTesting
        public DiffWriter(Command before, Command after)
        {
            this(after.txnId(), before, after);
        }

        @VisibleForTesting
        public DiffWriter(TxnId txnId, Command before, Command after)
        {
            this.txnId = txnId;
            this.before = before;
            this.after = after;
        }

        @VisibleForTesting
        public Command before()
        {
            return before;
        }

        @VisibleForTesting
        public Command after()
        {
            return after;
        }

        public void write(DataOutputPlus out, int userVersion) throws IOException
        {
            serialize(before, after, out, userVersion);
        }

        public TxnId key()
        {
            return txnId;
        }
    }

    @Nullable
    public static DiffWriter diff(Command original, Command current)
    {
        if (original == current
            || current == null
            || current.saveStatus() == SaveStatus.Uninitialised
            || !anyFieldChanged(original, current))
            return null;
        return new SavedCommand.DiffWriter(original, current);
    }

    // TODO (required): this is very inefficient
    private static boolean anyFieldChanged(Command before, Command after)
    {
        int flags = getFlags(before, after);
        for (Fields field : Fields.values())
        {
            if (getFieldChanged(field, flags))
                return true;
        }

        return false;
    }    
    
    public static void serialize(Command before, Command after, DataOutputPlus out, int userVersion) throws IOException
    {
        int flags = getFlags(before, after);

        out.writeInt(flags);

        // We encode all changed fields unless their value is null
        if (getFieldChanged(Fields.TXN_ID, flags) && after.txnId() != null)
            CommandSerializers.txnId.serialize(after.txnId(), out, userVersion);
        if (getFieldChanged(Fields.EXECUTE_AT, flags) && after.executeAt() != null)
            CommandSerializers.timestamp.serialize(after.executeAt(), out, userVersion);
        // TODO (desired): check if this can fold into executeAt
        if (getFieldChanged(Fields.EXECUTES_AT_LEAST, flags) && after.executesAtLeast() != null)
            CommandSerializers.timestamp.serialize(after.executesAtLeast(), out, userVersion);
        if (getFieldChanged(Fields.SAVE_STATUS, flags))
            out.writeInt(after.saveStatus().ordinal());
        if (getFieldChanged(Fields.DURABILITY, flags) && after.durability() != null)
            out.writeInt(after.durability().ordinal());

        if (getFieldChanged(Fields.ACCEPTED, flags) && after.acceptedOrCommitted() != null)
            CommandSerializers.ballot.serialize(after.acceptedOrCommitted(), out, userVersion);
        if (getFieldChanged(Fields.PROMISED, flags) && after.promised() != null)
            CommandSerializers.ballot.serialize(after.promised(), out, userVersion);

        if (getFieldChanged(Fields.PARTICIPANTS, flags) && after.participants() != null)
            CommandSerializers.participants.serialize(after.participants(), out, userVersion);
        if (getFieldChanged(Fields.PARTIAL_TXN, flags) && after.partialTxn() != null)
            CommandSerializers.partialTxn.serialize(after.partialTxn(), out, userVersion);
        if (getFieldChanged(Fields.PARTIAL_DEPS, flags) && after.partialDeps() != null)
            DepsSerializer.partialDeps.serialize(after.partialDeps(), out, userVersion);

        Command.WaitingOn waitingOn = getWaitingOn(after);
        if (getFieldChanged(Fields.WAITING_ON, flags) && waitingOn != null)
        {
            long size = WaitingOnSerializer.serializedSize(waitingOn);
            ByteBuffer serialized = WaitingOnSerializer.serialize(after.txnId(), waitingOn);
            out.writeInt((int) size);
            out.write(serialized);
        }

        if (getFieldChanged(Fields.WRITES, flags) && after.writes() != null)
            CommandSerializers.writes.serialize(after.writes(), out, userVersion);
    }

    @VisibleForTesting
    static int getFlags(Command before, Command after)
    {
        int flags = 0;

        flags = collectFlags(before, after, Command::txnId, true, Fields.TXN_ID, flags);
        flags = collectFlags(before, after, Command::executeAt, true, Fields.EXECUTE_AT, flags);
        flags = collectFlags(before, after, Command::executesAtLeast, true, Fields.EXECUTES_AT_LEAST, flags);
        flags = collectFlags(before, after, Command::saveStatus, false, Fields.SAVE_STATUS, flags);
        flags = collectFlags(before, after, Command::durability, false, Fields.DURABILITY, flags);

        flags = collectFlags(before, after, Command::acceptedOrCommitted, false, Fields.ACCEPTED, flags);
        flags = collectFlags(before, after, Command::promised, false, Fields.PROMISED, flags);

        flags = collectFlags(before, after, Command::participants, true, Fields.PARTICIPANTS, flags);
        flags = collectFlags(before, after, Command::partialTxn, false, Fields.PARTIAL_TXN, flags);
        flags = collectFlags(before, after, Command::partialDeps, false, Fields.PARTIAL_DEPS, flags);

        flags = collectFlags(before, after, SavedCommand::getWaitingOn, false, Fields.WAITING_ON, flags);

        flags = collectFlags(before, after, Command::writes, false, Fields.WRITES, flags);

        return flags;
    }

    static Command.WaitingOn getWaitingOn(Command command)
    {
        if (command instanceof Command.Committed)
            return command.asCommitted().waitingOn();

        return null;
    }

    private static <OBJ, VAL> int collectFlags(OBJ lo, OBJ ro, Function<OBJ, VAL> convert, boolean allowClassMismatch, Fields field, int oldFlags)
    {
        VAL l = null;
        VAL r = null;
        if (lo != null) l = convert.apply(lo);
        if (ro != null) r = convert.apply(ro);

        if (r == null)
            oldFlags = setFieldIsNull(field, oldFlags);

        if (l == r)
            return oldFlags; // no change

        if (l == null || r == null)
            return setFieldChanged(field, oldFlags);

        assert allowClassMismatch || l.getClass() == r.getClass() : String.format("%s != %s", l.getClass(), r.getClass());

        if (l.equals(r))
            return oldFlags; // no change

        return setFieldChanged(field, oldFlags);
    }

    private static int setFieldChanged(Fields field, int oldFlags)
    {
        return oldFlags | (1 << (field.ordinal() + Short.SIZE));
    }

    @VisibleForTesting
    static boolean getFieldChanged(Fields field, int oldFlags)
    {
        return (oldFlags & (1 << (field.ordinal() + Short.SIZE))) != 0;
    }

    @VisibleForTesting
    static boolean getFieldIsNull(Fields field, int oldFlags)
    {
        return (oldFlags & (1 << field.ordinal())) != 0;
    }

    private static int setFieldIsNull(Fields field, int oldFlags)
    {
        return oldFlags | (1 << field.ordinal());
    }

    private static int unsetFieldIsNull(Fields field, int oldFlags)
    {
        return oldFlags & ~(1 << field.ordinal());
    }

    public static class Builder
    {
        int flags;

        TxnId txnId;

        Timestamp executeAt;
        Timestamp executeAtLeast;
        SaveStatus saveStatus;
        Status.Durability durability;

        Ballot acceptedOrCommitted;
        Ballot promised;

        StoreParticipants participants;
        PartialTxn partialTxn;
        PartialDeps partialDeps;

        byte[] waitingOnBytes;
        SavedCommand.WaitingOnProvider waitingOn;
        Writes writes;
        Result result;

        boolean nextCalled;
        int count;

        public Builder()
        {
            clear();
        }

        public TxnId txnId()
        {
            return txnId;
        }

        public Timestamp executeAt()
        {
            return executeAt;
        }

        public Timestamp executeAtLeast()
        {
            return executeAtLeast;
        }

        public SaveStatus saveStatus()
        {
            return saveStatus;
        }

        public Status.Durability durability()
        {
            return durability;
        }

        public Ballot acceptedOrCommitted()
        {
            return acceptedOrCommitted;
        }

        public Ballot promised()
        {
            return promised;
        }

        public StoreParticipants participants()
        {
            return participants;
        }

        public PartialTxn partialTxn()
        {
            return partialTxn;
        }

        public PartialDeps partialDeps()
        {
            return partialDeps;
        }

        public SavedCommand.WaitingOnProvider waitingOn()
        {
            return waitingOn;
        }

        public Writes writes()
        {
            return writes;
        }

        public Result result()
        {
            return result;
        }

        public void clear()
        {
            flags = 0;

            txnId = null;

            executeAt = null;
            saveStatus = null;
            durability = null;

            acceptedOrCommitted = Ballot.ZERO;
            promised = null;

            participants = null;
            partialTxn = null;
            partialDeps = null;

            waitingOn = (txn, deps) -> null;
            writes = null;
            result = CommandSerializers.APPLIED;

            nextCalled = false;
            count = 0;
        }

        public boolean isEmpty()
        {
            return !nextCalled;
        }

        public int count()
        {
            return count;
        }

        public void serialize(DataOutputPlus out, int userVersion) throws IOException
        {
            out.writeInt(flags);

            // We encode all changed fields unless their value is null
            if (getFieldChanged(Fields.TXN_ID, flags) && !getFieldIsNull(Fields.TXN_ID, flags))
                CommandSerializers.txnId.serialize(txnId(), out, userVersion);
            if (getFieldChanged(Fields.EXECUTE_AT, flags) && !getFieldIsNull(Fields.EXECUTE_AT, flags))
                CommandSerializers.timestamp.serialize(executeAt(), out, userVersion);
            // TODO (desired): check if this can fold into executeAt
            if (getFieldChanged(Fields.EXECUTES_AT_LEAST, flags) && !getFieldIsNull(Fields.EXECUTES_AT_LEAST, flags))
                CommandSerializers.timestamp.serialize(executeAtLeast(), out, userVersion);
            if (getFieldChanged(Fields.SAVE_STATUS, flags) && !getFieldIsNull(Fields.SAVE_STATUS, flags))
                out.writeInt(saveStatus().ordinal());
            if (getFieldChanged(Fields.DURABILITY, flags) && !getFieldIsNull(Fields.DURABILITY, flags))
                out.writeInt(durability().ordinal());

            if (getFieldChanged(Fields.ACCEPTED, flags) && !getFieldIsNull(Fields.ACCEPTED, flags))
                CommandSerializers.ballot.serialize(acceptedOrCommitted(), out, userVersion);
            if (getFieldChanged(Fields.PROMISED, flags) && !getFieldIsNull(Fields.PROMISED, flags))
                CommandSerializers.ballot.serialize(promised(), out, userVersion);

            if (getFieldChanged(Fields.PARTICIPANTS, flags) && !getFieldIsNull(Fields.PARTICIPANTS, flags))
                CommandSerializers.participants.serialize(participants(), out, userVersion);
            if (getFieldChanged(Fields.PARTIAL_TXN, flags) && !getFieldIsNull(Fields.PARTIAL_TXN, flags))
                CommandSerializers.partialTxn.serialize(partialTxn(), out, userVersion);
            if (getFieldChanged(Fields.PARTIAL_DEPS, flags) && !getFieldIsNull(Fields.PARTIAL_DEPS, flags))
                DepsSerializer.partialDeps.serialize(partialDeps(), out, userVersion);

            if (getFieldChanged(Fields.WAITING_ON, flags) && !getFieldIsNull(Fields.WAITING_ON, flags))
            {
                out.writeInt(waitingOnBytes.length);
                out.write(waitingOnBytes);
            }

            if (getFieldChanged(Fields.WRITES, flags) && !getFieldIsNull(Fields.WRITES, flags))
                CommandSerializers.writes.serialize(writes(), out, userVersion);
        }


        // TODO: we seem to be writing some form of empty transaction
        @SuppressWarnings({ "rawtypes", "unchecked" })
        public void deserializeNext(DataInputPlus in, int userVersion) throws IOException
        {
            final int flags = in.readInt();
            nextCalled = true;
            count++;

            for (Fields field : Fields.values())
            {
                if (getFieldChanged(field, flags))
                {
                    this.flags = setFieldChanged(field, this.flags);
                    if (getFieldIsNull(field, flags))
                        this.flags = setFieldIsNull(field, this.flags);
                    else
                        this.flags = unsetFieldIsNull(field, this.flags);
                }
            }

            if (getFieldChanged(Fields.TXN_ID, flags))
            {
                if (getFieldIsNull(Fields.TXN_ID, flags))
                    txnId = null;
                else
                    txnId = CommandSerializers.txnId.deserialize(in, userVersion);
            }

            if (getFieldChanged(Fields.EXECUTE_AT, flags))
            {
                if (getFieldIsNull(Fields.EXECUTE_AT, flags))
                    executeAt = null;
                else
                    executeAt = CommandSerializers.timestamp.deserialize(in, userVersion);
            }

            if (getFieldChanged(Fields.EXECUTES_AT_LEAST, flags))
            {
                if (getFieldIsNull(Fields.EXECUTES_AT_LEAST, flags))
                    executeAtLeast = null;
                else
                    executeAtLeast = CommandSerializers.timestamp.deserialize(in, userVersion);
            }

            if (getFieldChanged(Fields.SAVE_STATUS, flags))
            {
                if (getFieldIsNull(Fields.SAVE_STATUS, flags))
                    saveStatus = null;
                else
                    saveStatus = SaveStatus.values()[in.readInt()];
            }
            if (getFieldChanged(Fields.DURABILITY, flags))
            {
                if (getFieldIsNull(Fields.DURABILITY, flags))
                    durability = null;
                else
                    durability = Status.Durability.values()[in.readInt()];
            }

            if (getFieldChanged(Fields.ACCEPTED, flags))
            {
                if (getFieldIsNull(Fields.ACCEPTED, flags))
                    acceptedOrCommitted = null;
                else
                    acceptedOrCommitted = CommandSerializers.ballot.deserialize(in, userVersion);
            }

            if (getFieldChanged(Fields.PROMISED, flags))
            {
                if (getFieldIsNull(Fields.PROMISED, flags))
                    promised = null;
                else
                    promised = CommandSerializers.ballot.deserialize(in, userVersion);
            }

            if (getFieldChanged(Fields.PARTICIPANTS, flags))
            {
                if (getFieldIsNull(Fields.PARTICIPANTS, flags))
                    participants = null;
                else
                    participants = CommandSerializers.participants.deserialize(in, userVersion);
            }

            if (getFieldChanged(Fields.PARTIAL_TXN, flags))
            {
                if (getFieldIsNull(Fields.PARTIAL_TXN, flags))
                    partialTxn = null;
                else
                    partialTxn = CommandSerializers.partialTxn.deserialize(in, userVersion);
            }

            if (getFieldChanged(Fields.PARTIAL_DEPS, flags))
            {
                if (getFieldIsNull(Fields.PARTIAL_DEPS, flags))
                    partialDeps = null;
                else
                    partialDeps = DepsSerializer.partialDeps.deserialize(in, userVersion);
            }

            if (getFieldChanged(Fields.WAITING_ON, flags))
            {
                if (getFieldIsNull(Fields.WAITING_ON, flags))
                {
                    waitingOn = null;
                }
                else
                {
                    int size = in.readInt();
                    waitingOnBytes = new byte[size];
                    in.readFully(waitingOnBytes);
                    ByteBuffer buffer = ByteBuffer.wrap(waitingOnBytes);
                    waitingOn = (localTxnId, deps) -> {
                        try
                        {
                            return WaitingOnSerializer.deserialize(localTxnId, deps.keyDeps.keys(), deps.rangeDeps, deps.directKeyDeps, buffer);
                        }
                        catch (IOException e)
                        {
                            throw Throwables.unchecked(e);
                        }
                    };
                }
            }

            if (getFieldChanged(Fields.WRITES, flags))
            {
                if (getFieldIsNull(Fields.WRITES, flags))
                    writes = null;
                else
                    writes = CommandSerializers.writes.deserialize(in, userVersion);
            }
            
        }

        public void forceResult(Result newValue)
        {
            this.result = newValue;
        }

        public Command construct()
        {
            if (!nextCalled)
                return null;

            CommonAttributes.Mutable attrs = new CommonAttributes.Mutable(txnId);
            if (partialTxn != null)
                attrs.partialTxn(partialTxn);
            if (durability != null)
                attrs.durability(durability);
            if (participants != null)
                attrs.setParticipants(participants);
            if (partialDeps != null &&
                (saveStatus.known.deps != NoDeps &&
                 saveStatus.known.deps != DepsErased &&
                 saveStatus.known.deps != DepsUnknown))
                attrs.partialDeps(partialDeps);

            Command.WaitingOn waitingOn = null;
            if (this.waitingOn != null)
                waitingOn = this.waitingOn.provide(txnId, partialDeps);

            switch (saveStatus.status)
            {
                case NotDefined:
                    return saveStatus == SaveStatus.Uninitialised ? Command.NotDefined.uninitialised(attrs.txnId())
                                                                  : Command.NotDefined.notDefined(attrs, promised);
                case PreAccepted:
                    return Command.PreAccepted.preAccepted(attrs, executeAt, promised);
                case AcceptedInvalidate:
                case Accepted:
                case PreCommitted:
                    if (saveStatus == SaveStatus.AcceptedInvalidate)
                        return Command.AcceptedInvalidateWithoutDefinition.acceptedInvalidate(attrs, promised, acceptedOrCommitted);
                    else
                        return Command.Accepted.accepted(attrs, saveStatus, executeAt, promised, acceptedOrCommitted);
                case Committed:
                case Stable:
                    return Command.Committed.committed(attrs, saveStatus, executeAt, promised, acceptedOrCommitted, waitingOn);
                case PreApplied:
                case Applied:
                    return Command.Executed.executed(attrs, saveStatus, executeAt, promised, acceptedOrCommitted, waitingOn, writes, result);
                case Truncated:
                case Invalidated:
                    return truncated(attrs, saveStatus, executeAt, executeAtLeast, writes, result);
                default:
                    throw new IllegalStateException();
            }
        }

        private static Command.Truncated truncated(CommonAttributes.Mutable attrs, SaveStatus status, Timestamp executeAt, Timestamp executesAtLeast, Writes writes, Result result)
        {
            switch (status)
            {
                default:
                    throw illegalState("Unhandled SaveStatus: " + status);
                case TruncatedApplyWithOutcome:
                case TruncatedApplyWithDeps:
                case TruncatedApply:
                    if (attrs.txnId().kind().awaitsOnlyDeps())
                        return Command.Truncated.truncatedApply(attrs, status, executeAt, writes, result, executesAtLeast);
                    return Command.Truncated.truncatedApply(attrs, status, executeAt, writes, result, null);
                case ErasedOrVestigial:
                    return Command.Truncated.erasedOrInvalidOrVestigial(attrs.txnId(), attrs.durability(), attrs.participants());
                case Erased:
                    return Command.Truncated.erased(attrs.txnId(), attrs.durability(), attrs.participants());
                case Invalidated:
                    return Command.Truncated.invalidated(attrs.txnId());
            }
        }

        public String toString()
        {
            return "Diff {" +
                   "txnId=" + txnId +
                   ", executeAt=" + executeAt +
                   ", saveStatus=" + saveStatus +
                   ", durability=" + durability +
                   ", acceptedOrCommitted=" + acceptedOrCommitted +
                   ", promised=" + promised +
                   ", participants=" + participants +
                   ", partialTxn=" + partialTxn +
                   ", partialDeps=" + partialDeps +
                   ", waitingOn=" + waitingOn +
                   ", writes=" + writes +
                   '}';
        }
    }

    public interface WaitingOnProvider
    {
        Command.WaitingOn provide(TxnId txnId, PartialDeps deps);
    }
}