package io.simonis.crac.impl;

import io.simonis.crac.CheckpointException;
import io.simonis.crac.Context;
import io.simonis.crac.Resource;
import io.simonis.crac.RestoreException;
import io.simonis.utils.Logger;

import java.lang.ref.WeakReference;

import java.util.ArrayList;
import java.util.Collections;

public class FirecrackerContext extends Context<Resource> {

    private final Logger log = Logger.getLogger(getClass());

    private final ArrayList<WeakReference<Resource>> resources = new ArrayList<>();

    @Override
    public synchronized void register(Resource resource) {
        log.info("Registering resource {}", resource);
        log.debug("  from:", new Exception("Registering resource"));
        resources.add(new WeakReference<Resource>(resource));
    }

    @Override
    public synchronized void beforeCheckpoint(Context<? extends Resource> context) throws CheckpointException {
        ArrayList<Throwable> exceptions = new ArrayList<>();
        for (var iterator = resources.listIterator(resources.size()); iterator.hasPrevious();) {
            Resource r = iterator.previous().get();
            if (r != null) {
                try {
                    log.info("Calling beforeCheckpoint() for resource {}", r);
                    log.debug("  from:", new Exception("beforeCheckpoint()"));
                    r.beforeCheckpoint(this);
                } catch (CheckpointException ce) {
                    Collections.addAll(exceptions, ce.getSuppressed());
                } catch (Exception e) {
                    exceptions.add(e);
                }
            }
        }
        if (!exceptions.isEmpty()) {
            CheckpointException ce = new CheckpointException();
            for (Throwable t: exceptions) {
                ce.addSuppressed(t);
            }
            log.info("Errors while executing beforeCheckpoint():", ce);
            throw ce;
        }
    }

    @Override
    public synchronized void afterRestore(Context<? extends Resource> context) throws RestoreException {
        ArrayList<Throwable> exceptions = new ArrayList<>();
        for (var iterator = resources.listIterator(); iterator.hasNext();) {
            Resource r = iterator.next().get();
            if (r != null) {
                try {
                    log.info("Calling afterRestore() for resource {}", r);
                    log.debug("  from:", new Exception("afterRestore()"));
                    r.afterRestore(this);
                } catch (RestoreException re) {
                    Collections.addAll(exceptions, re.getSuppressed());
                } catch (Exception e) {
                    exceptions.add(e);
                }
            }
        }
        if (!exceptions.isEmpty()) {
            RestoreException re = new RestoreException();
            for (Throwable t: exceptions) {
                re.addSuppressed(t);
            }
            log.info("Errors while executing afterRestore():", re);
            throw re;
        }
    }
}
