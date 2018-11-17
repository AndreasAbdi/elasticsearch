package org.elasticsearch.gradle;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import org.apache.tools.ant.taskdefs.condition.Os;
import org.elasticsearch.gradle.test.GradleUnitTestCase;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
public class DependenciesInfoTaskTests extends GradleUnitTestCase {
    //TODO: write tests with custom test file, standard test files(MIT/LGPL/CDDL/etc), and no test file/directory.
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();


    public void testCheckDependencyInfoWhenNoDependency() throws Exception {
        //TODO
    }

    public void testCheckDependencyInfoWithDependencies() throws Exception {
        //TODO
    }

    public void testCheckDependencyInfoWithCompileOnlyDependencies() throws Exception {
        //TODO
    }

    public void testCheckDependencyInfoWithVariantDependencies() throws Exception {
        //TODO
    }

    public void testGetLicenseTypeWithCustomLicense() throws Exception {
        //TODO
    }

    public void testGetLicenseTypeWithStandardLicense() throws Exception {
        //TODO
    }

    public void testGetLicenseTypeWithNoLicense() throws Exception {
        //TODO
    }

    public void testCheckPermissionsWhenNoFileExists() throws Exception {
        RandomizedTest.assumeFalse("Functionality is Unix specific", Os.isFamily(Os.FAMILY_WINDOWS));
        Project project = createProject();
        DependenciesInfoTask dependenciesInfoTask = createTask(project);
        dependenciesInfoTask.generateDependenciesInfo();
    }


    private Project createProject() throws IOException {
        Project project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build();
        project.getPlugins().apply(JavaPlugin.class);
        return project;
    }

    private DependenciesInfoTask createTask(Project project) {
        return project.getTasks().create("dependenciesInfoTask", DependenciesInfoTask.class);
    }
}
