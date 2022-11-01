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

import com.oracle.svm.hosted.meta.HostedMethod;
import org.graalvm.collections.Pair;
import org.graalvm.compiler.api.directives.GraalDirectives;
import org.graalvm.compiler.core.common.CompilationIdentifier;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.CallTargetNode;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.phases.common.AbstractInliningPhase;
import org.graalvm.compiler.phases.common.CanonicalizerPhase;
import org.graalvm.compiler.phases.common.DeadCodeEliminationPhase;
import org.graalvm.compiler.phases.common.inlining.InliningUtil;
import org.graalvm.compiler.phases.tiers.HighTierContext;
import org.graalvm.compiler.truffle.compiler.phases.TruffleHostInliningPhase;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.truffle.api.HostCompilerDirectives.BytecodeInterpreterSwitch;

import jdk.vm.ci.meta.ResolvedJavaMethod;

import java.util.ArrayList;
import java.util.List;

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
        if (graph.isSupernodeTarget) {
            System.out.println("replacement started for target " + graph.method().getDeclaringClass() + graph.method().getName());
            graph.getDebug().forceDump(graph, "parent graph pre replacements");
            var klass = graph.method().getDeclaringClass().getSuperclass();

            var supernodeAnnot = klass.getAnnotation(GraalDirectives.Supernode.class);
            var resolvedSupernodeMethods = getResolvedJavaMethodsFromSupernode(supernodeAnnot);
            var methodName = supernodeAnnot.methodsName();

            // fetching graphs for each children (for now only one)
            var supernodeChildrenGraphs = new StructuredGraph[] {parseGraph(context, graph, resolvedSupernodeMethods.getLeft().get(0))};

            for (var supernodeChildGraph: supernodeChildrenGraphs) {
                var replacementsList = resolvedSupernodeMethods.getRight();
                replaceExecuteCallsWithDirect(context, supernodeChildGraph, replacementsList, methodName);

                for (Node node: graph.getNodes()) {
                    if (!(node instanceof Invoke))
                        continue;
                    Invoke invoke = (Invoke) node;
                    ResolvedJavaMethod targetMethod = invoke.getTargetMethod();

                    if (targetMethod.getName().equals(methodName)) {
                        InliningUtil.inline(invoke, supernodeChildGraph, false, resolvedSupernodeMethods.getLeft().get(0));
                        System.out.println("REPLACEMENT DONE for " + methodName + " in parent graph (" +
                                graph.method().getDeclaringClass().getName() + graph.method().getName() + ")");
                        break;
                    }
                }
            }
            graph.getDebug().forceDump(graph, "parent graph post replacements");
        }
    }

    protected StructuredGraph parseGraph(HighTierContext context, StructuredGraph graph, ResolvedJavaMethod method) {
        return ((HostedMethod) method).compilationInfo.createGraph(graph.getDebug(), CompilationIdentifier.INVALID_COMPILATION_ID, true);
    }


    Pair<List<ResolvedJavaMethod>, List<ResolvedJavaMethod>> getResolvedJavaMethodsFromSupernode(GraalDirectives.Supernode supernode) {
        var children = supernode.forChildren();
        var replacements = supernode.replaceChildWith();

        List<ResolvedJavaMethod> childrenMethods = new ArrayList<>();
        for (var child: children) {
            var meth = getMethodFromClass(child, supernode.methodsName());
            if (meth == null)
                System.out.println("no method found for " + child.getName());
            childrenMethods.add(meth);
        }

        List<ResolvedJavaMethod> replacementsList = new ArrayList<>();
        for (var child: replacements) {
            var meth = getMethodFromClass(child, supernode.methodsName());
            if (meth == null)
                System.out.println("no method found for " + child.getName());
            replacementsList.add(meth);
        }

        return Pair.create(childrenMethods, replacementsList);
    }

    ResolvedJavaMethod getMethodFromClass(Class<?> klass, String methodName) {
        ResolvedJavaMethod correctMethod = null;
        String className = klass.getName();

        if (methodName.equals("executeLong")) {
            // copying because apparently we get concurrent modification errors otherwise? which is very weird?
            var copiedList = new ArrayList<>(StructuredGraph.executeLongList);
            for (var executeLong : copiedList) {
                var type = executeLong.getDeclaringClass();
                if (type.toString().contains(className))
                    correctMethod = executeLong;
            }

        } else if (methodName.equals("executeDouble")) {
            var copiedList2 = new ArrayList<>(StructuredGraph.executeDoubleList);
            for (var executeDouble : copiedList2) {
                var type = executeDouble.getDeclaringClass();
                if (type.toString().contains(className))
                    correctMethod = executeDouble;
            }
        } else
            throw new UnsupportedOperationException("can't currently replace any other method than an executeLong or executeDouble");

        return correctMethod;
    }

    private StructuredGraph replaceExecuteCallsWithDirect(HighTierContext context, StructuredGraph inlineGraph,
                                                          List<ResolvedJavaMethod> replacementsList, String methodName) {
        inlineGraph.getDebug().forceDump(inlineGraph, "before our changes");

        for (Node node: inlineGraph.getNodes()) {
            if (!(node instanceof Invoke))
                continue;
            Invoke invoke = (Invoke) node;
            ResolvedJavaMethod targetMethod = invoke.getTargetMethod();

            if (!targetMethod.getName().equals(methodName)) {
                continue;
            }

            Node loadFieldNode = node;
            for (int i = 0; i < 3; i++) {
                loadFieldNode = loadFieldNode.predecessor();
            }

            if (!(loadFieldNode instanceof LoadFieldNode))
                continue;

            var fieldName = ((LoadFieldNode)loadFieldNode).field().getName();

            // we can check if the field has the @Child annotation instead maybe
            if (!fieldName.equals("receiver_") && !fieldName.equals("argument_")) {
                continue;
            }

            ResolvedJavaMethod overrideMethod = fieldName.equals("receiver_") ? replacementsList.get(0) : replacementsList.get(1);

            if (overrideMethod == null) {
                System.out.println("should be unreachable: method not found");
                return inlineGraph;
            }

            if (!overrideMethod.canBeStaticallyBound()) {
                System.out.println("should be unreachable: method can't be statically bound");
                return inlineGraph;
            }

//            System.out.println("pre replacement: " + invoke.getTargetMethod().getDeclaringClass().getName() + invoke.getTargetMethod().getName());
//            InliningUtil.replaceInvokeCallTarget(invoke, inlineGraph, InvokeKind.Special, overrideMethod);
//            System.out.println("post replacement and pre-fuckup: " + invoke.getTargetMethod().getDeclaringClass().getName() + invoke.getTargetMethod().getName());

            StructuredGraph newGraph = parseGraph(context, inlineGraph, overrideMethod);
            inlineGraph.getDebug().forceDump(inlineGraph, "graph preinlining");

            InliningUtil.inline(invoke, newGraph, false, overrideMethod);
//            CallTree overrideMethodCallTree = new CallTree(overrideMethod);
//            inline(context, null, overrideMethodCallTree);
//            System.out.println(node.getNodeClass().toString());
//            inlineGraph.removeFixed((FixedWithNextNode) node);
            inlineGraph.getDebug().forceDump(inlineGraph, "graph post inlining");

            System.out.println("Successful replacement and inlining from " + targetMethod.getName()
                    + " in (" + inlineGraph.method().getDeclaringClass().getName() + inlineGraph.method().getName() + ")"
                    + " to " + overrideMethod.getDeclaringClass().getName() + overrideMethod.getName());
        }

        new DeadCodeEliminationPhase(Optional).apply(inlineGraph);
        canonicalizer.apply(inlineGraph, context);

        inlineGraph.getDebug().forceDump(inlineGraph, "after our changes");

        return inlineGraph;
    }
}