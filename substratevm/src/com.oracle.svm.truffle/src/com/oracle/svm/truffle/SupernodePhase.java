/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.truffle;

import org.graalvm.compiler.core.common.CompilationIdentifier;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.CallTargetNode;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.phases.common.AbstractInliningPhase;
import org.graalvm.compiler.phases.common.CanonicalizerPhase;
import org.graalvm.compiler.phases.common.DeadCodeEliminationPhase;
import org.graalvm.compiler.phases.common.inlining.InliningUtil;
import org.graalvm.compiler.phases.tiers.HighTierContext;
import org.graalvm.compiler.truffle.compiler.phases.TruffleHostInliningPhase;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.truffle.api.HostCompilerDirectives.BytecodeInterpreterSwitch;

import jdk.vm.ci.meta.ResolvedJavaMethod;

import static org.graalvm.compiler.phases.common.DeadCodeEliminationPhase.Optionality.Optional;

/**
 * Overrides the behavior of the Truffle host inlining phase taking into account that SVM does not
 * need any graph caching as all graphs are already parsed. Also enables the use of this phase for
 * methods that are runtime compiled in addition to methods annotated with
 * {@link BytecodeInterpreterSwitch}.
 */
@Platforms(Platform.HOSTED_ONLY.class)
public final class SupernodePhase extends AbstractInliningPhase {

    CanonicalizerPhase canonicalizer;

    HighTierContext context;

    public SupernodePhase(CanonicalizerPhase canonicalizer) {
        this.canonicalizer = canonicalizer;
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        if (!graph.shouldBeDevirtualizedLong)// && !graph.shouldBeDevirtualizedDouble)
            return;

        this.context = context;
        replaceExecuteCallsWithDirect(graph);
    }

    private StructuredGraph replaceExecuteCallsWithDirect(StructuredGraph graph) {
        for (Node node: graph.getNodes()) {
            if (!(node instanceof Invoke)) {
                continue;
            }

            Invoke invoke = (Invoke) node;
            ResolvedJavaMethod targetMethod = invoke.getTargetMethod();

            if (graph.shouldBeDevirtualizedLong && !(targetMethod.getName().equals("executeLong")))
                return graph;

            if (graph.shouldBeDevirtualizedDouble && !(targetMethod.getName().equals("executeDouble")))
                return graph;

            ResolvedJavaMethod overrideMethod = null;

            if (graph.shouldBeDevirtualizedLong)
                overrideMethod = StructuredGraph.argumentReadV2NodeExecuteLong;
//            else if (graph.shouldBeDevirtualizedDouble)
//                overrideMethod = StructuredGraph.argumentReadV2NodeExecuteDouble;

            if (overrideMethod == null) {
                System.out.println("should be unreachable: method not found");
                return graph;
            }

            if (!overrideMethod.canBeStaticallyBound()) {
                System.out.println("should be unreachable: method can't be statically bound");
                return graph;
            }

            final DebugContext debug = graph.getDebug();
            debug.forceDump(graph, "Before supernode inlining");

            InliningUtil.replaceInvokeCallTarget(invoke, graph, CallTargetNode.InvokeKind.Special, overrideMethod);

            StructuredGraph newGraph = parseGraph(this.context, graph, invoke.getTargetMethod());
            InliningUtil.inline(invoke, newGraph, false, invoke.getTargetMethod(), "SupernodePhase", "SupernodePhase");


            System.out.println("Successful replacement and inlining from " + targetMethod.getName()
                    + " in (" + graph.method().getDeclaringClass().getName() + graph.method().getName() + ")"
                    + " to " + overrideMethod.getDeclaringClass().getName() + overrideMethod.getName());

            debug.forceDump(graph, "After supernode inlining");
        }

        return graph;
    }

    protected StructuredGraph parseGraph(HighTierContext context, StructuredGraph graph, ResolvedJavaMethod method) {
        return ((HostedMethod) method).compilationInfo.createGraph(graph.getDebug(), CompilationIdentifier.INVALID_COMPILATION_ID, true);
    }
}
