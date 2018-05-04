/*
 * Copyright (c) 2016-2017, Excelsior LLC.
 *
 *  This file is part of Excelsior JET API.
 *
 *  Excelsior JET API is free software:
 *  you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Excelsior JET API is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Excelsior JET API.
 *  If not, see <http://www.gnu.org/licenses/>.
 *
*/
package com.excelsiorjet.api.tasks;

import com.excelsiorjet.api.ExcelsiorJet;
import com.excelsiorjet.api.tasks.config.compiler.ExecProfilesConfig;
import com.excelsiorjet.api.tasks.config.compiler.WindowsVersionInfoConfig;
import com.excelsiorjet.api.tasks.config.compiler.StackTraceSupportType;
import com.excelsiorjet.api.util.Utils;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.stream.Collectors.joining;

/**
 * Generates Excelsior JET compiler project files and provides other arguments for the compiler derived
 * from the given {@link JetProject}.
 *
 * @author Aleksey Zhidkov
 */
class CompilerArgsGenerator {

    private final JetProject project;

    private final ExcelsiorJet excelsiorJet;

    private final boolean buildToProfile;

    CompilerArgsGenerator(JetProject project, ExcelsiorJet excelsiorJet, boolean buildToProfile) {
        this.project = project;
        this.excelsiorJet = excelsiorJet;
        this.buildToProfile = buildToProfile;
    }

    private String toJetPrjFormat(Path f) {
        return f.toString().replace(File.separatorChar, '/');
    }

    private String toJetPrjFormat(File f) {
        return toJetPrjFormat(f.toPath());
    }

    String projectFileContent() {
        StringWriter stringWriter = new StringWriter();
        PrintWriter out = new PrintWriter(stringWriter);

        compilerArgs().forEach(out::println);
        if (project.compilerOptions() != null) {
            for (String option : project.compilerOptions()) {
                out.println(option);
            }
        }

        for (ClasspathEntry dep : project.classpathEntries()) {
            switch (project.appType()) {
                case PLAIN:
                case DYNAMIC_LIBRARY:
                case WINDOWS_SERVICE:
                    out.println("!classpathentry " + toJetPrjFormat(project.toPathRelativeToJetBuildDir(dep)));
                    break;
                case TOMCAT:
                    String warDeployName = project.tomcatConfiguration().warDeployName;
                    String entryPath = ":/WEB-INF/";
                    if (dep.isMainArtifact) {
                        entryPath += "classes";
                    } else {
                        entryPath += "lib/" + dep.path.getName();
                    }
                    out.println("!classloaderentry webapp webapps/" + warDeployName.substring(0, warDeployName.lastIndexOf(".war")) + entryPath);
                    break;
                default:
                    throw new AssertionError("Unknown app type");
            }
            if (dep.optimize != null) {
                out.println("  -optimize=" + dep.optimize.jetValue);
            }
            if (dep.protect != null) {
                out.println("  -protect=" + dep.protect.jetValue);
            }
            if (dep.pack != null) {
                out.println("  -pack=" + dep.pack.jetValue);
            }
            out.println("!end");
        }

        for (String mod : modules()) {
            out.println("!module " + mod);
        }

        return stringWriter.toString();
    }

    private List<String> modules() {
        ArrayList<String> modules = new ArrayList<>();

        if (excelsiorJet.getTargetOS().isWindows()) {
            if (project.icon() != null) {
                modules.add(toJetPrjFormat(project.icon()));
            }
        }

        ExecProfilesConfig execProfiles = project.execProfiles();
        if (execProfiles.getUsg().exists()) {
            modules.add(toJetPrjFormat(execProfiles.getUsg()));
        }

        return modules;
    }

    private List<String> compilerArgs() {
        ArrayList<String> compilerArgs = new ArrayList<>();

        switch (project.appType()) {
            case PLAIN:
                compilerArgs.add("-main=" + project.mainClass());

                if (project.splash() != null) {
                    compilerArgs.add("-splash=" + project.splash().getAbsolutePath());
                } else {
                    compilerArgs.add("-splashgetfrommanifest+");
                }

                break;
            case DYNAMIC_LIBRARY:
                compilerArgs.add("-gendll+");
                break;
            case WINDOWS_SERVICE:
                compilerArgs.add("-servicemain=" + project.mainClass());
                compilerArgs.add("-servicename=" + project.windowsServiceConfiguration().name);
                break;

            case TOMCAT:
                compilerArgs.add("-apptype=tomcat");
                compilerArgs.add("-appdir=" + toJetPrjFormat(project.tomcatInBuildDir()));
                if (project.tomcatConfiguration().hideConfig) {
                    compilerArgs.add("-hideconfiguration+");
                }
                if (!project.tomcatConfiguration().genScripts) {
                    compilerArgs.add("-gentomcatscripts-");
                }
                break;
            default:
                throw new AssertionError("Unknown app type");
        }

        if (!project.pdbConfiguration().keepInBuildDir) {
            compilerArgs.add("-pdblocation=" + project.pdbConfiguration().pdbLocation().getAbsolutePath().replace(File.separatorChar, '/'));
        }

        //hideConsole handling
        if (excelsiorJet.getTargetOS().isWindows() && project.hideConsole()) {
            switch (project.appType()) {
                case WINDOWS_SERVICE:
                    if (!project.multiApp()) {
                        //hiding console for windows services make sense only for multiApp
                        break;
                    }
                    //fall through
                case PLAIN:
                case TOMCAT:
                    compilerArgs.add("-gui+");
                    break;
                case DYNAMIC_LIBRARY:
                    break;
                default:
                    throw new AssertionError("Unknown app type");
            }
        }


        compilerArgs.add("-outputname=" + project.outputName());
        compilerArgs.add("-decor=ht");

        if (project.profileStartup() && !buildToProfile) {
            compilerArgs.add("-saprofmode=ALWAYS");
            compilerArgs.add("-saproftimeout=" + project.profileStartupTimeout());
        }

        if (project.isAddWindowsVersionInfo()) {
            WindowsVersionInfoConfig versionInfo = project.windowsVersionInfoConfiguration();
            compilerArgs.add("-versioninfocompanyname=" + versionInfo.company);
            compilerArgs.add("-versioninfoproductname=" + versionInfo.product);
            compilerArgs.add("-versioninfoproductversion=" + versionInfo.version);
            compilerArgs.add("-versioninfolegalcopyright=" + versionInfo.copyright);
            compilerArgs.add("-versioninfofiledescription=" + versionInfo.description);
        }

        if (project.multiApp()) {
            compilerArgs.add("-multiapp+");
        }

        if (project.globalOptimizer()) {
            compilerArgs.add("-global+");
        }

        if (project.trialVersion() != null) {
            compilerArgs.add("-expire=" + project.trialVersion().getExpire());
            compilerArgs.add("-expiremsg=" + project.trialVersion().expireMessage);
        }

        if (project.protectData()) {
            compilerArgs.add("-cryptseed=" + project.cryptSeed());
        }

        ExecProfilesConfig execProfiles = project.execProfiles();
        if (excelsiorJet.isStartupProfileGenerationSupported()) {
            if (execProfiles.getStartup().exists()) {
                compilerArgs.add("-startupprofile=" + execProfiles.getStartup().getAbsolutePath());
            }
        }

        if (excelsiorJet.isPGOSupported() && !buildToProfile) {
            if (execProfiles.getJProfile().exists()) {
                compilerArgs.add("-jprofile=" + execProfiles.getJProfile().getAbsolutePath());
                compilerArgs.add("-pgo+");
            }
        }

        if (project.runtimeConfiguration().flavor != null) {
            compilerArgs.add("-jetrt=" + project.runtimeConfiguration().flavor);
        }

        switch (project.inlineExpansion()) {
            case TINY_METHODS_ONLY:
                compilerArgs.add("-inline-");
                break;
            case LOW:
                compilerArgs.add("-inlinelimit=50");
                compilerArgs.add("-inlinetolimit=250");
                break;
            case MEDIUM:
                compilerArgs.add("-inlinelimit=100");
                compilerArgs.add("-inlinetolimit=500");
                break;
            case VERY_AGGRESSIVE:
                compilerArgs.add("-inlinelimit=250");
                compilerArgs.add("-inlinetolimit=2000");
            case AGGRESSIVE:
                //use default
                break;
            default:
                throw new AssertionError("Unknown inline expansion type: " + project.inlineExpansion());
        }

        if (!project.stackAllocation()) {
            compilerArgs.add("-genstackalloc-");
        }

        if (!Utils.isEmpty(project.exeRunArgs())) {
            String quotedArgs = Arrays.stream(project.exeRunArgs())
                    .map(Utils::quoteCmdLineArgument)
                    .collect(joining(" "));
            compilerArgs.add("-runarguments=" + quotedArgs);
        }

        if (project.stackTraceSupport() == StackTraceSupportType.FULL) {
            compilerArgs.add("-genstacktrace+");

        }

        // JVM args may contain $(Root) prefix for system property value
        // (that should expand to installation directory location).
        // However JET compiler replaces such occurrences with s value of "Root" equation if the "$(Root)" is
        // used in the project file.
        // So we need to pass jetvmprop as separate compiler argument as workaround.
        // We also write the equation in commented form to the project in order to see it in the technical support.
        compilerArgs.add("%" + jetVMPropOpt());

        return compilerArgs;
    }

    private List<String> jvmArgs() {
        List<String> jvmArgs = project.jvmArgs() != null ? new ArrayList<>(Arrays.asList(project.jvmArgs())) : new ArrayList<>();
        if (project.stackTraceSupport() == StackTraceSupportType.NONE) {
            jvmArgs.add("-Djet.stack.trace=false");
        }
        if (buildToProfile) {
            jvmArgs.add("-Djet.profiler");
            if (project.isProfileLocally()) {
                jvmArgs.add("-Djet.jprof.name=" + project.execProfiles().getJProfile().getAbsolutePath());
            } else {
                jvmArgs.add("-Djet.jprof.name=" + project.execProfiles().getJProfile().getName());
            }
        }
        return jvmArgs;
    }

    String jetVMPropOpt() {
        return "-jetvmprop=" + String.join(" ", jvmArgs());
    }
}
