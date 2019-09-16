/*
 * Copyright (c) 2019, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.wasm.emcc_env;

import com.oracle.truffle.wasm.binary.WasmCodeEntry;
import com.oracle.truffle.wasm.binary.WasmModule;
import com.oracle.truffle.wasm.binary.WasmRootNode;
import com.oracle.truffle.wasm.emcc_env.functions.Abort;
import com.oracle.truffle.wasm.emcc_env.functions.EmscriptenMemcpyBig;
import com.oracle.truffle.wasm.emcc_env.functions.NoOp;
import com.oracle.truffle.wasm.emcc_env.functions.WasiFdWrite;

public class WasmEnv {
    public static void importFunction(String functionName, WasmModule module, WasmRootNode rootNode, WasmCodeEntry codeEntry) {
        switch (functionName) {
            case "abort": {
                rootNode.setBody(new Abort(module, codeEntry));
                break;
            }
            case "_emscripten_memcpy_big": {
                rootNode.setBody(new EmscriptenMemcpyBig(module, codeEntry));
                break;
            }
            case "___wasi_fd_write": {
                rootNode.setBody(new WasiFdWrite(module, codeEntry));

                break;
            }
            default: {
                rootNode.setBody(new NoOp(module, codeEntry));
                codeEntry.initStackSlots(rootNode.getFrameDescriptor(), 1);
            }
        }
    }
}
