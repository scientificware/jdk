/*
 * Copyright (c) 2002, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package nsk.jdi.VoidValue.toString;

import nsk.share.*;
import nsk.share.jpda.*;
import nsk.share.jdi.*;

import com.sun.jdi.*;
import com.sun.jdi.request.*;
import com.sun.jdi.event.*;
import com.sun.jdi.connect.*;
import java.io.*;
import java.util.*;

/**
 * The debugger application of the test.
 */
public class tostring001 {

    //------------------------------------------------------- immutable common fields

    final static String SIGNAL_READY = "ready";
    final static String SIGNAL_GO    = "go";
    final static String SIGNAL_QUIT  = "quit";

    private static int waitTime;
    private static int exitStatus;
    private static ArgumentHandler     argHandler;
    private static Log                 log;
    private static Debugee             debuggee;
    private static ReferenceType       debuggeeClass;

    //------------------------------------------------------- mutable common fields

    private final static String prefix = "nsk.jdi.VoidValue.toString.";
    private final static String className = "tostring001";
    private final static String debuggerName = prefix + className;
    private final static String debuggeeName = debuggerName + "a";

    //------------------------------------------------------- test specific fields

    /** debuggee's methods for check **/
    private final static String checkedMethods[] = {
        "Mv" ,  "MvS",  "MvI",  "MvY",  "MvU",
        "MvR",  "MvP",  "MvSM", "MvIM", "MvYM", "MvPM"
                                                   };

    //------------------------------------------------------- immutable common methods

    public static void main(String argv[]) {
        int result = run(argv,System.out);
        if (result != 0) {
            throw new RuntimeException("TEST FAILED with result " + result);
        }
    }

    private static void display(String msg) {
        log.display("debugger > " + msg);
    }

    private static void complain(String msg) {
        log.complain("debugger FAILURE > " + msg);
    }

    public static int run(String argv[], PrintStream out) {

        exitStatus = Consts.TEST_PASSED;

        argHandler = new ArgumentHandler(argv);
        log = new Log(out, argHandler);
        waitTime = argHandler.getWaitTime() * 60000;

        debuggee = Debugee.prepareDebugee(argHandler, log, debuggeeName);

        debuggeeClass = debuggee.classByName(debuggeeName);
        if ( debuggeeClass == null ) {
            complain("Class '" + debuggeeName + "' not found.");
            exitStatus = Consts.TEST_FAILED;
        }

        execTest();

        debuggee.quit();

        return exitStatus;
    }

    //------------------------------------------------------ mutable common method

    private static void execTest() {

        BreakpointRequest brkp = debuggee.setBreakpoint(debuggeeClass,
                                                    tostring001a.brkpMethodName,
                                                    tostring001a.brkpLineNumber);
        debuggee.resume();

        debuggee.sendSignal(SIGNAL_GO);
        Event event = null;

        // waiting the breakpoint event
        try {
            event = debuggee.waitingEvent(brkp, waitTime);
        } catch (InterruptedException e) {
            throw new Failure("unexpected InterruptedException while waiting for Breakpoint event");
        }
        if (!(event instanceof BreakpointEvent)) {
            debuggee.resume();
            throw new Failure("BreakpointEvent didn't arrive");
        }

        ThreadReference thread = ((BreakpointEvent)event).thread();
        List<Value> params = new Vector<Value>();
        ClassType testedClass = (ClassType)debuggeeClass;
        ObjectReference testedObject = null;

        // Finding of debuggee's class constructor
        Method ctor = debuggee.methodByName(debuggeeClass, "<init>");

        try {
            testedObject = testedClass.newInstance(thread, ctor, params, 0);
            // Disable collection on testedObject. invokeMethod() will essentially do a
            // vm.resume(), which gives GC a chance to run, which might result in this
            // object being collected.
            testedObject.disableCollection();
        } catch (Exception e) {
            throw new Failure("unexpected " + e + " when invoking debuggee's constructor");
        }

        display("Checking toString() method for debuggee's void value methods...");

        // Check all methods from debuggee
        for (int i = 0; i < checkedMethods.length-1; i++) {

            VoidValue voidValue = null;
            Method method;

            method = debuggee.methodByName(debuggeeClass, checkedMethods[i]);
            try {
                if (method.isStatic()) {
                    voidValue = (VoidValue) testedClass.invokeMethod(thread, method, params, 0);
                } else {
                    voidValue = (VoidValue) testedObject.invokeMethod(thread, method, params, 0);
                }

                String str = voidValue.toString();
                if (str == null) {
                    complain("toString() returns null for VoidValue of debugges's method: " + checkedMethods[i]);
                    exitStatus = Consts.TEST_FAILED;
                } else if (str.length() == 0) {
                    complain("toString() returns empty string for VoidValue of debugges's method: " + checkedMethods[i]);
                    exitStatus = Consts.TEST_FAILED;
                } else {
                    display("toString() returns for VoidValue of debugges's method " + checkedMethods[i] + " : " + str);
                }
            } catch(Exception e) {
                complain("Unexpected " + e + " when invoking debuggee's method: " + checkedMethods[i]);
                exitStatus = Consts.TEST_FAILED;
            }

        }

        display("Checking of debuggee's void value methods completed!");
        testedObject.enableCollection();
        debuggee.resume();
    }

    //--------------------------------------------------------- test specific methods
}
//--------------------------------------------------------- test specific classes
