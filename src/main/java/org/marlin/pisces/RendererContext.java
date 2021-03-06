/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.marlin.pisces;

import java.awt.geom.FastPath2D;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.marlin.pisces.ArrayCache.*;
import org.marlin.pisces.PiscesRenderingEngine.NormalizingPathIterator;
import static org.marlin.pisces.PiscesUtils.getCallerInfo;
import static org.marlin.pisces.PiscesUtils.logInfo;

/**
 * This class is a renderer context dedicated to a single thread (using thread local)
 */
final class RendererContext implements PiscesConst {

    private static final String className = RendererContext.class.getName();
    /** RendererContext created counter */
    private static final AtomicInteger contextCount = new AtomicInteger(1);
    /** RendererContext statistics */
    static final RendererStats stats = (doStats || doMonitors) ? new RendererStats() : null;

    /**
     * Create a new renderer context
     *
     * @return new RendererContext instance
     */
    static RendererContext createContext() {
        final RendererContext newCtx = new RendererContext("ctx" + Integer.toString(contextCount.getAndIncrement()));
        if (RendererContext.stats != null) {
            RendererContext.stats.allContexts.add(newCtx);
        }
        return newCtx;
    }

    /* members */
    /** context name (debugging purposes) */
    final String name;
    /**
     * Reference to this instance (hard, soft or weak). 
     * @see PiscesRenderingEngine#REF_TYPE
     */
    final Object reference;
    /** dynamic array caches */
    final IntArrayCache[] intArrayCaches = new IntArrayCache[BUCKETS];
    final FloatArrayCache[] floatArrayCaches = new FloatArrayCache[BUCKETS];
    /* dirty instances (use them carefully) */
    /** dynamic DIRTY byte array for PiscesCache */
    final ByteArrayCache[] dirtyArrayCaches = new ByteArrayCache[BUCKETS];
    /* shared data */
    /* fixed arrays (dirty) */
    final float[] float6 = new float[6];
    /** shared curve (dirty) (Renderer / Stroker) */
    final Curve curve = new Curve();
    /* pisces class instances */
    /** PiscesRenderingEngine.NormalizingPathIterator */
    final NormalizingPathIterator npIterator;
    /** PiscesRenderingEngine.TransformingPathConsumer2D */
    final TransformingPathConsumer2D transformerPC2D;
    /** recycled Path2D instance */
    FastPath2D p2d = null;
    /** Renderer */
    final Renderer renderer;
    /** Stroker */
    final Stroker stroker;
    /** Simplifies out collinear lines */
    final CollinearSimplifier simplifier = new CollinearSimplifier();
    /** Dasher */
    final Dasher dasher;
    /** PiscesTileGenerator */
    final PiscesTileGenerator ptg;
    /** PiscesCache */
    final PiscesCache piscesCache;

    /**
     * Constructor
     *
     * @param name
     */
    RendererContext(final String name) {
        if (logCreateContext) {
            PiscesUtils.logInfo("new RendererContext = " + name);
        }

        this.name = name;

        for (int i = 0; i < BUCKETS; i++) {
            intArrayCaches[i] = new IntArrayCache(ARRAY_SIZES[i]);
            floatArrayCaches[i] = new FloatArrayCache(ARRAY_SIZES[i]);
            // dirty:
            dirtyArrayCaches[i] = new ByteArrayCache(DIRTY_ARRAY_SIZES[i]);
        }

        // PiscesRenderingEngine.NormalizingPathIterator:
        npIterator = new NormalizingPathIterator(this);

        // PiscesRenderingEngine.TransformingPathConsumer2D
        transformerPC2D = new TransformingPathConsumer2D();

        // Renderer:
        piscesCache = new PiscesCache(this);
        renderer = new Renderer(this); // needs piscesCache
        ptg = new PiscesTileGenerator(renderer);

        stroker = new Stroker(this);
        dasher = new Dasher(this);

        // Create the reference to this instance (hard, soft or weak):
        switch (PiscesRenderingEngine.REF_TYPE) {
            default:
            case PiscesRenderingEngine.REF_HARD:
                reference = this;
                break;
            case PiscesRenderingEngine.REF_SOFT:
                reference = new SoftReference<RendererContext>(this);
                break;
            case PiscesRenderingEngine.REF_WEAK:
                reference = new WeakReference<RendererContext>(this);
                break;
        }
    }

    /* Array caches */
    IntArrayCache getIntArrayCache(final int length) {
        final int bucket = ArrayCache.getBucket(length);
        return intArrayCaches[bucket];
    }

    FloatArrayCache getFloatArrayCache(final int length) {
        final int bucket = ArrayCache.getBucket(length);
        return floatArrayCaches[bucket];
    }

    ByteArrayCache getDirtyArrayCache(final int length) {
        final int bucket = ArrayCache.getBucketDirty(length);
        return dirtyArrayCaches[bucket];
    }

    /* dirty piscesCache */
    byte[] getDirtyArray(final int length) {
        if (length <= MAX_DIRTY_ARRAY_SIZE) {
            return getDirtyArrayCache(length).getArray();
        }

        if (doStats) {
            oversize++;
        }

        if (doLogOverSize) {
            logInfo("getDirtyIntArray[oversize]: length=\t" + length + "\tfrom=\t" + getCallerInfo(className));
        }

        return new byte[length];
    }

    void putDirtyArray(final byte[] array) {
        final int length = array.length;
        if (((length & 0x1) == 0) && (length <= MAX_DIRTY_ARRAY_SIZE)) {
            getDirtyArrayCache(length).putDirtyArray(array, length);
        }
    }

    /* TODO: replace with new signature */
    byte[] widenDirtyArray(final byte[] in, final int cursize, final int numToAdd) {
        final int length = in.length;
        final int newSize = cursize + numToAdd;
        if (length >= newSize) {
            return in;
        }

        final byte[] res = RendererContext.this.widenDirtyArray(in, length, cursize, newSize);

        if (doLog) {
            logInfo("widenDirtyArray int[" + res.length + "]: cursize=\t" + cursize + "\tlength=\t" + length
                    + "\tnew length=\t" + newSize + "\tfrom=\t" + getCallerInfo(className));
        }
        return res;
    }

    private byte[] widenDirtyArray(final byte[] in, final int length, final int usedSize, final int newSize) {
        if (doChecks && length >= newSize) {
            return in;
        }
        if (doStats) {
            resizeDirty++;
        }

        // maybe change bucket:
        final byte[] res = getDirtyArray(2 * newSize); // Use x2

        System.arraycopy(in, 0, res, 0, usedSize); // copy only used elements

        // maybe return current array:
        putDirtyArray(in); // NO clear array data = DIRTY ARRAY ie manual clean when getting an array!!

        return res;
    }

    /* int array piscesCache */
    int[] getIntArray(final int length) {
        if (length <= MAX_ARRAY_SIZE) {
            return getIntArrayCache(length).getArray();
        }

        if (doStats) {
            oversize++;
        }

        if (doLogOverSize) {
            logInfo("getIntArray[oversize]: length=\t" + length + "\tfrom=\t" + getCallerInfo(className));
        }

        return new int[length];
    }

    /* TODO: replace with new signature */
    int[] widenArray(final int[] in, final int length, final int usedSize, final int newSize, final int clearTo) {
        if (doChecks && length >= newSize) {
            return in;
        }
        if (doStats) {
            resizeInt++;
        }

        // maybe change bucket:
        final int[] res = getIntArray(getNewSize(newSize)); // Use GROW or x2

        System.arraycopy(in, 0, res, 0, usedSize); // copy only used elements

        // maybe return current array:
        putIntArray(in, 0, clearTo); // ensure all array is cleared (grow-reduce algo)

        return res;
    }

    int[] widenArrayPartially(final int[] in, final int length, final int fromIndex, final int toIndex, final int newSize) {
        if (doChecks && length >= newSize) {
            return in;
        }
        if (doStats) {
            resizeInt++;
        }

        // maybe change bucket:
        final int[] res = getIntArray(getNewSize(newSize)); // Use GROW or x2

        System.arraycopy(in, fromIndex, res, fromIndex, toIndex - fromIndex); // copy only used elements

        // maybe return current array:
        putIntArray(in, fromIndex, toIndex); // only partially cleared

        return res;
    }

    void putIntArray(final int[] array, final int fromIndex, final int toIndex) {
        final int length = array.length;
        if (((length & 0x1) == 0) && (length <= MAX_ARRAY_SIZE)) {
            getIntArrayCache(length).putArray(array, length, fromIndex, toIndex);
        }
    }

    /* float array piscesCache */
    float[] getFloatArray(final int length) {
        if (length <= MAX_ARRAY_SIZE) {
            return getFloatArrayCache(length).getArray();
        }

        if (doStats) {
            oversize++;
        }

        if (doLogOverSize) {
            logInfo("getFloatArray[oversize]: length=\t" + length + "\tfrom=\t" + getCallerInfo(className));
        }

        return new float[length];
    }

    /* TODO: replace with new signature */
    float[] widenArray(final float[] in, final int length, final int usedSize, final int newSize, final int clearTo) {
        if (doChecks && length >= newSize) {
            return in;
        }
        if (doStats) {
            resizeFloat++;
        }

        // maybe change bucket:
        final float[] res = getFloatArray(getNewSize(newSize)); // Use GROW or x2

        System.arraycopy(in, 0, res, 0, usedSize); // copy only used elements

        // maybe return current array:
        putFloatArray(in, 0, clearTo); // ensure all array is cleared (grow-reduce algo)

        return res;
    }

    void putFloatArray(final float[] array, final int fromIndex, final int toIndex) {
        final int length = array.length;
        if (((length & 0x1) == 0) && (length <= MAX_ARRAY_SIZE)) {
            getFloatArrayCache(length).putArray(array, length, fromIndex, toIndex);
        }
    }
}
