/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.jvm.tasks.api;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class StubClassWriter extends ClassVisitor implements Opcodes {

    public static final String UOE_METHOD = "$unsupportedOpEx";
    private String internalClassName;

    public StubClassWriter(ClassWriter cv) {
        super(ASM5, cv);
    }

    /**
     * Generates an exception which is going to be thrown in each method. The reason it is in a separate method is because it reduces the bytecode size.
     */
    private void generateUnsupportedOperationExceptionMethod() {
        MethodVisitor mv = cv.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, UOE_METHOD, "()Ljava/lang/UnsupportedOperationException;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
        mv.visitInsn(DUP);
        mv.visitLdcInsn("You tried to call a method on an API class. You probably added the API jar on classpath instead of the implementation jar.");
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(3, 0);
        mv.visitEnd();
    }


    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        internalClassName = name;
        if ((access & ACC_INTERFACE) == 0) {
            generateUnsupportedOperationExceptionMethod();
        }
    }

    @Override
    public MethodVisitor visitMethod(final int access, final String name, final String desc, final String signature, final String[] exceptions) {
        MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
        if ((access & ACC_ABSTRACT) == 0) {
            mv.visitCode();
            mv.visitMethodInsn(INVOKESTATIC, internalClassName, UOE_METHOD, "()Ljava/lang/UnsupportedOperationException;", false);
            mv.visitInsn(ATHROW);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
        }
        return mv;
    }

}