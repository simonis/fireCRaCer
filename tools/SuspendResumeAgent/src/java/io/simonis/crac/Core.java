// Copyright 2017-2020 Azul Systems, Inc.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice,
// this list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice,
// this list of conditions and the following disclaimer in the documentation
// and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package io.simonis.crac;

import io.simonis.crac.impl.FirecrackerContext;
import io.simonis.utils.Logger;

/**
 * The coordination service.
 */
public class Core {
    static private final Context<Resource> firecrackerContext = new FirecrackerContext();
    static private final Logger log = Logger.getLogger(Core.class);

    /**
     * Gets the global {@code Context} for checkpoint/restore notifications.
     *
     * @return the global {@code Context}
     */
    public static Context<Resource> getGlobalContext() {
        return firecrackerContext;
    }

    /**
     * Requests checkpoint and returns upon a successful restore.
     * May throw an exception if the checkpoint or restore are unsuccessful.
     *
     * @throws CheckpointException           if an exception occurred during checkpoint
     *                                       notification and the execution continues in the original Java instance.
     * @throws RestoreException              if an exception occurred during restore
     *                                       notification and execution continues in a new Java instance.
     * @throws UnsupportedOperationException if checkpoint/restore is not
     *                                       supported, no notification performed and the execution continues in
     *                                       the original Java instance.
     */
    public static void checkpointRestore() throws
            CheckpointException, RestoreException {
        log.info("Starting checkpoint");
        log.debug("  from:", new Exception("Starting checkpoint"));
        try {
            firecrackerContext.beforeCheckpoint(null);
        } catch (CheckpointException ce) {
            log.error("Error when calling beforeCheckpoint()");
            return;
        }
        log.info("Checkpointed");
        log.info("Starting restore");
        log.debug("  from:", new Exception("Starting restore"));
        try {
            firecrackerContext.afterRestore(null);
        } catch (RestoreException re) {
            log.error("Error when calling afterRestore()");
            return;
        }
        log.info("Checkpoint restored");
    }
}
