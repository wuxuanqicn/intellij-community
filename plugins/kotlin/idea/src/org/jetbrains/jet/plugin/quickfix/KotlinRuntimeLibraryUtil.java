/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.plugin.quickfix;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.roots.AnnotationOrderRootType;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.impl.libraries.ProjectLibraryTable;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packaging.impl.elements.ManifestFileUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.resolve.java.JvmStdlibNames;
import org.jetbrains.jet.utils.PathUtil;

import java.io.File;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import static org.jetbrains.jet.plugin.project.JsModuleDetector.isJsModule;

public class KotlinRuntimeLibraryUtil {
    public static final String LIBRARY_NAME = "KotlinRuntime";
    public static final String KOTLIN_RUNTIME_JAR = "kotlin-runtime.jar";
    public static final String UNKNOWN_VERSION = "UNKNOWN";

    private KotlinRuntimeLibraryUtil() {}

    public static void addJdkAnnotations(@NotNull Module module) {
        Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
        assert sdk != null;
        File annotationsIoFile = PathUtil.getKotlinPathsForIdeaPlugin().getJdkAnnotationsPath();
        if (annotationsIoFile.exists()) {
            VirtualFile jdkAnnotationsJar = LocalFileSystem.getInstance().findFileByIoFile(annotationsIoFile);
            if (jdkAnnotationsJar != null) {
                SdkModificator modificator = sdk.getSdkModificator();
                modificator.addRoot(JarFileSystem.getInstance().getJarRootForLocalFile(jdkAnnotationsJar),
                                    AnnotationOrderRootType.getInstance());
                modificator.commitChanges();
            }
        }
    }

    public static boolean jdkAnnotationsArePresent(@NotNull Module module) {
        Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
        if (sdk == null) return false;
        return ContainerUtil.exists(sdk.getRootProvider().getFiles(AnnotationOrderRootType.getInstance()),
                                    new Condition<VirtualFile>() {
                                        @Override
                                        public boolean value(VirtualFile file) {
                                            return PathUtil.JDK_ANNOTATIONS_JAR.equals(file.getName());
                                        }
                                    });
    }

    public static boolean isModuleAlreadyConfigured(Module module) {
        return isMavenModule(module) || isJsModule(module) || isWithJavaModule(module);
    }

    private static boolean isMavenModule(@NotNull Module module) {
        // This constant could be acquired from MavenProjectsManager, but we don't want to depend on the Maven plugin...
        // See MavenProjectsManager.isMavenizedModule()
        return "true".equals(module.getOptionValue("org.jetbrains.idea.maven.project.MavenProjectsManager.isMavenModule"));
    }

    private static boolean isWithJavaModule(Module module) {
        // Can find a reference to kotlin class in module scope
        GlobalSearchScope scope = module.getModuleWithDependenciesAndLibrariesScope(false);
        return (JavaPsiFacade.getInstance(module.getProject()).findClass(JvmStdlibNames.JET_OBJECT.getFqName().getFqName(), scope) != null);
    }

    static void setUpKotlinRuntimeLibrary(
            @NotNull final Module module,
            @NotNull final Library library,
            @NotNull final Runnable afterSetUp
    ) {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
            @Override
            public void run() {
                ModifiableRootModel model = ModuleRootManager.getInstance(module).getModifiableModel();
                if (model.findLibraryOrderEntry(library) == null) {
                    model.addLibraryEntry(library);
                    model.commit();
                }
                else {
                    model.dispose();
                }

                afterSetUp.run();

                if (!jdkAnnotationsArePresent(module)) {
                    addJdkAnnotations(module);
                }
            }
        });
    }

    @Nullable
    static Library findOrCreateRuntimeLibrary(@NotNull Project project, @NotNull FindRuntimeLibraryHandler handler) {
        final LibraryTable table = ProjectLibraryTable.getInstance(project);
        final Library kotlinRuntime = table.getLibraryByName(LIBRARY_NAME);
        if (kotlinRuntime != null) {
            for (VirtualFile root : kotlinRuntime.getFiles(OrderRootType.CLASSES)) {
                if (root.getName().equals(KOTLIN_RUNTIME_JAR)) {
                    return kotlinRuntime;
    }
            }
        }

        File runtimePath = PathUtil.getKotlinPathsForIdeaPlugin().getRuntimePath();
        if (!runtimePath.exists()) {
            handler.runtimePathDoesNotExist(runtimePath);
            return null;
        }

        final File targetJar = handler.getRuntimeJarPath();
        if (targetJar == null) return null;
        try {
            FileUtil.copy(runtimePath, targetJar);
            VirtualFile jarVfs = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetJar);
            if (jarVfs != null) {
                jarVfs.refresh(false, false);
            }
        }
        catch (IOException e) {
            handler.ioExceptionOnCopyingJar(e);
            return null;
        }

        return ApplicationManager.getApplication().runWriteAction(new Computable<Library>() {
            @Override
            public Library compute() {
                Library result = kotlinRuntime == null
                                 ? table.createLibrary("KotlinRuntime")
                                 : kotlinRuntime;

                Library.ModifiableModel model = result.getModifiableModel();
                model.addRoot(VfsUtil.getUrlForLibraryRoot(targetJar), OrderRootType.CLASSES);
                model.addRoot(VfsUtil.getUrlForLibraryRoot(targetJar) + "src", OrderRootType.SOURCES);
                model.commit();
                return result;
            }
        });
    }

    public static void updateRuntime(
            @NotNull final Project project,
            @NotNull final Runnable jarNotFoundHandler
    ) {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
                File runtimePath = PathUtil.getKotlinPathsForIdeaPlugin().getRuntimePath();
                if (!runtimePath.exists()) {
                    jarNotFoundHandler.run();
                    return;
                }
                VirtualFile runtimeJar = getKotlinRuntimeJar(project);
                assert runtimeJar != null;
                VirtualFile jarFile = JarFileSystem.getInstance().getVirtualFileForJar(runtimeJar);
                if (jarFile != null) {
                    runtimeJar = jarFile;
                }

                try {
                    FileUtil.copy(runtimePath, new File(runtimeJar.getPath()));
                }
                catch (IOException e) {
                    throw new AssertionError(e);
                }
                runtimeJar.refresh(true, true);
            }
        });
    }

    @Nullable
    public static String getRuntimeVersion(@NotNull Project project) {
        VirtualFile kotlinRuntimeJar = getKotlinRuntimeJar(project);
        if (kotlinRuntimeJar == null) return null;
        VirtualFile manifestFile = kotlinRuntimeJar.findFileByRelativePath(JarFile.MANIFEST_NAME);
        if (manifestFile != null) {
            Attributes attributes = ManifestFileUtil.readManifest(manifestFile).getMainAttributes();
            if (attributes.containsKey(Attributes.Name.IMPLEMENTATION_VERSION)) {
                return attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            }
        }
        return UNKNOWN_VERSION;
    }

    @Nullable
    private static VirtualFile getKotlinRuntimeJar(@NotNull Project project) {
        LibraryTable table = ProjectLibraryTable.getInstance(project);
        Library kotlinRuntime = table.getLibraryByName(LIBRARY_NAME);
        if (kotlinRuntime != null) {
            for (VirtualFile root : kotlinRuntime.getFiles(OrderRootType.CLASSES)) {
                if (root.getName().equals(KOTLIN_RUNTIME_JAR)) {
                    return root;
                }
            }
        }
        return null;
    }

    public static abstract class FindRuntimeLibraryHandler {
        @Nullable
        public abstract File getRuntimeJarPath();

        public void runtimePathDoesNotExist(@NotNull File path) {
        }

        public void ioExceptionOnCopyingJar(@NotNull IOException e) {
        }
    }
}
