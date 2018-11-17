package org.elasticsearch.gradle;

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

import org.apache.commons.io.FileUtils;
import org.elasticsearch.gradle.precommit.DependencyLicensesTask;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A task to gather information about the dependencies and export them into a csv file.
 *
 * The following information is gathered:
 * <ul>
 *     <li>name: name that identifies the library (groupId:artifactId)</li>
 *     <li>version</li>
 *     <li>URL: link to have more information about the dependency.</li>
 *     <li>license: <a href="https://spdx.org/licenses/">SPDX license</a> identifier, custom license or UNKNOWN.</li>
 * </ul>
 *
 */
public class DependenciesInfoTask extends DefaultTask {

    /** Dependencies to gather information from. */
    @Input
    public Configuration runtimeConfiguration;

    /** We subtract compile-only dependencies. */
    @Input
    public Configuration compileOnlyConfiguration;

    @Input
    public LinkedHashMap<String, String> mappings;

    /** Directory to read license files */
    @InputDirectory
    public File licensesDir = new File(getProject().getProjectDir(), "licenses");

    @OutputFile
    File outputFile = new File(getProject().getBuildDir(), "reports/dependencies/dependencies.csv");

    public DependenciesInfoTask() {
        setDescription("Create a CSV file with dependencies information.");
    }

    @TaskAction
    public void generateDependenciesInfo() {

        final DependencySet runtimeDependencies = runtimeConfiguration.getAllDependencies();
        // we have to resolve the transitive dependencies and create a group:artifactId:version map
        final Set<String> compileOnlyArtifacts =
            compileOnlyConfiguration
                .getResolvedConfiguration()
                .getResolvedArtifacts()
                .stream()
                .map(config -> {
                    return Arrays.asList(
                        config.getModuleVersion().getId().getGroup(),
                        config.getModuleVersion().getId().getName(),
                        config.getModuleVersion().getId().getVersion())
                    .stream()
                    .collect(Collectors.joining(":"));
                })
                .collect(Collectors.toSet());


        final StringBuilder output = new StringBuilder();

        for (final Dependency dependency : runtimeDependencies) {
            // we do not need compile-only dependencies here
            if (compileOnlyArtifacts.contains(String.format("%s:%s:%s", dependency.getGroup(), dependency.getName(), dependency.getVersion()))) {
                continue;
            }
            // only external dependencies are checked
            if (dependency.getGroup() != null && dependency.getGroup().contains("org.elasticsearch")) {
                continue;
            }

            final String url = createURL(dependency.getGroup(), dependency.getName(), dependency.getVersion());
            final String dependencyName = DependencyLicensesTask.getDependencyName(mappings, dependency.getName());
            getLogger().info(String.format("mapped dependency %s:%s to %s for license info", dependency.getGroup(), dependency.getName(), dependencyName));

            //"mapped dependency ${dependency.group}:${dependency.name} to ${dependencyName} for license info");
            final String licenseType = getLicenseType(dependency.getGroup(), dependencyName);
            output.append(String.format("%s:%s,%s,%s,%s", dependency.getGroup(), dependency.getName(), dependency.getVersion(), url, licenseType));
        }

        try {
            FileUtils.write(outputFile, output.toString(), "UTF-8");
        } catch(IOException exception) {
            getLogger().error(exception.getMessage());
        }
    }

    /**
     * Create an URL on <a href="https://repo1.maven.org/maven2/">Maven Central</a>
     * based on dependency coordinates.
     */
    protected String createURL(final String group, final String name, final String version){
        final String baseURL = "https://repo1.maven.org/maven2";
        return String.format("%s/%s/%s/%s", baseURL, group.replaceAll("\\.", "/"), name, version);
    }

    /**
     * Read the LICENSE file associated with the dependency and determine a license type.
     *
     * The license type is one of the following values:
     * <u>
     *     <li><em>UNKNOWN</em> if LICENSE file is not present for this dependency.</li>
     *     <li><em>one SPDX identifier</em> if the LICENSE content matches with an SPDX license.</li>
     *     <li><em>Custom;URL</em> if it's not an SPDX license,
     *          URL is the Github URL to the LICENSE file in elasticsearch repository.</li>
     * </ul>
     *
     * @param group dependency group
     * @param name dependency name
     * @return SPDX identifier, UNKNOWN or a Custom license
     */
    protected String getLicenseType(final String group, final String name) {
        String unknown = "UNKNOWN";
        try (final Stream<Path> stream = Files.list(licensesDir.toPath())) {
            final PathMatcher licenseFilter = licensesDir.toPath().getFileSystem().getPathMatcher("glob:*-LICENSE*");
            return stream
                .filter(licenseFilter::matches)
                .filter(path -> {
                    String prefix = path.getFileName().toString().split("-LICENSE.*")[0];
                    return group.contains(prefix) || name.contains(prefix);
                })
                .findFirst()
                .flatMap(this::pathToLicenseType)
                .orElse(unknown);
        } catch (IOException exception) {
            getLogger().error("failed to obtain files in licenses directory");
        }

        return unknown;
    }

    /**
     * Check the license content to identify an SPDX license type.
     *
     * @param licenseText LICENSE file content.
     * @return SPDX identifier or null.
     */
    private Optional<String> checkSPDXLicense(final String licenseText) {
        return getSPDXIdentifierToLicenseRegexMap()
            .entrySet()
            .stream()
            .filter(entry -> licenseText.matches(entry.getValue()))
            .map(entry -> entry.getKey())
            .findFirst();
    }

    private Optional<String> pathToLicenseType(Path licenseFile) {
        // replace * because they are sometimes used at the beginning lines as if the license was a multi-line comment
        try {
            String fileContents = Files.readAllLines(licenseFile)
                .stream()
                .map(line -> {
                    return line
                        .replaceAll("\\s+", " ")
                        .replaceAll("\\*", " "); })
                .collect(Collectors.joining());


            Optional<String> spdx = checkSPDXLicense(fileContents);
            if(spdx.isPresent()) {
                return spdx;
            } else {
                return getCustomLicenseType(licenseFile);
            }
        } catch (IOException exception) {
            getLogger().error("failed to retrieve contents of license file", exception);
        }
        return Optional.empty();

    }

    private Optional<String> getCustomLicenseType(Path licenseFile) {
        // License has not be identified as SPDX.
        // As we have the license file, we create a Custom entry with the URL to this license file.
        final String gitBranch = System.getProperty("build.branch', 'master");
        final String githubBaseURL = String.format("https://raw.githubusercontent.com/elastic/elasticsearch/%s/", gitBranch);
        try {
            return Optional.of(String.format("Custom;", licenseFile
                .toRealPath()
                .toString()
                .replaceFirst(".*/elasticsearch/", githubBaseURL)));
        } catch(IOException exception) {
            getLogger().error("failed to get license file's real path", exception);
        }
        return Optional.empty();


    }
    private Map<String, String> getSPDXIdentifierToLicenseRegexMap() {
        Map<String, String> spdxToRegex = generateSPDXToRegexBaseMap();
        return surroundRegexWithAnyMatch(spdxToRegex);
    }

    private Map<String, String> surroundRegexWithAnyMatch(Map<String, String> SPDXToRegex ) {
        return SPDXToRegex
            .entrySet()
            .stream()
            .map(entry -> new AbstractMap.SimpleEntry<>(
                entry.getKey(),
                String.format(".*%s.*",entry.getValue())))
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue));
    }

    private Map<String, String> generateSPDXToRegexBaseMap() {
        Map<String, String> spdxToRegex = new HashMap<>();
        final String APACHE_2_0 = "Apache.*License.*(v|V)ersion.*2\\.0";

        final String BSD_2 = String.join("\n"
            , "Redistribution and use in source and binary forms, with or without"
            , "modification, are permitted provided that the following conditions"
            , "are met:"

            , "1\\. Redistributions of source code must retain the above copyright"
            , "notice, this list of conditions and the following disclaimer\\."
            , "2\\. Redistributions in binary form must reproduce the above copyright"
            , "notice, this list of conditions and the following disclaimer in the"
            , "documentation and/or other materials provided with the distribution\\."

            , "THIS SOFTWARE IS PROVIDED BY .+ (``|''|\")AS IS(''|\") AND ANY EXPRESS OR"
            , "IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES"
            , "OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED\\."
            , "IN NO EVENT SHALL .+ BE LIABLE FOR ANY DIRECT, INDIRECT,"
            , "INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES \\(INCLUDING, BUT"
            , "NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,"
            , "DATA, OR PROFITS; OR BUSINESS INTERRUPTION\\) HOWEVER CAUSED AND ON ANY"
            , "THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT"
            , "\\(INCLUDING NEGLIGENCE OR OTHERWISE\\) ARISING IN ANY WAY OUT OF THE USE OF"
            , "THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE\\."
        ).replaceAll("\\s+", "\\\\s*");

        final String BSD_3 = String.join(
            "Redistribution and use in source and binary forms, with or without"
            , "modification, are permitted provided that the following conditions"
            , "are met:"

            , "(1\\.)? Redistributions of source code must retain the above copyright"
            , "notice, this list of conditions and the following disclaimer\\."
            , "(2\\.)? Redistributions in binary form must reproduce the above copyright"
            , "notice, this list of conditions and the following disclaimer in the"
            , "documentation and/or other materials provided with the distribution\\."
            , "((3\\.)? The name of .+ may not be used to endorse or promote products"
            , "derived from this software without specific prior written permission\\.|"
            , "(3\\.)? Neither the name of .+ nor the names of its"
            , "contributors may be used to endorse or promote products derived from"
            , "this software without specific prior written permission\\.)"

            , "THIS SOFTWARE IS PROVIDED BY .+ (``|''|\")AS IS(''|\") AND ANY EXPRESS OR"
            , "IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES"
            , "OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED\\."
            , "IN NO EVENT SHALL .+ BE LIABLE FOR ANY DIRECT, INDIRECT,"
            , "INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES \\(INCLUDING, BUT"
            , "NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,"
            , "DATA, OR PROFITS; OR BUSINESS INTERRUPTION\\) HOWEVER CAUSED AND ON ANY"
            , "THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT"
            , "\\(INCLUDING NEGLIGENCE OR OTHERWISE\\) ARISING IN ANY WAY OUT OF THE USE OF"
            , "THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE\\."
        ).replaceAll("\\s+", "\\\\s*");

        final String CDDL_1_0 = "COMMON DEVELOPMENT AND DISTRIBUTION LICENSE.*Version 1.0";
        final String CDDL_1_1 = "COMMON DEVELOPMENT AND DISTRIBUTION LICENSE.*Version 1.1";
        final String ICU = "ICU License - ICU 1.8.1 and later";
        final String LGPL_3 = "GNU LESSER GENERAL PUBLIC LICENSE.*Version 3";

        final String MIT = String.join(
            "Permission is hereby granted, free of charge, to any person obtaining a copy of"
            , "this software and associated documentation files \\(the \"Software\"\\), to deal in"
            , "the Software without restriction, including without limitation the rights to"
            , "use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies"
            , "of the Software, and to permit persons to whom the Software is furnished to do"
            , "so, subject to the following conditions:"

            , "The above copyright notice and this permission notice shall be included in all"
            , "copies or substantial portions of the Software\\."

            , "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR"
            , "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,"
            , "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT\\. IN NO EVENT SHALL THE"
            , "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER"
            , "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,"
            , "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE"
            , "SOFTWARE\\."
        ).replaceAll("\\s+", "\\\\s*");
        final String MOZILLA_1_1 = "Mozilla Public License.*Version 1.1";
        final String MOZILLA_2_0 = "Mozilla\\s*Public\\s*License\\s*Version\\s*2\\.0";
        spdxToRegex.put("Apache-2.0", APACHE_2_0);
        spdxToRegex.put("MIT", MIT);
        spdxToRegex.put("BSD-2-Clause", BSD_2);
        spdxToRegex.put("BSD-3-Clause", BSD_3);
        spdxToRegex.put("LGPL-3.0", LGPL_3);
        spdxToRegex.put("CDDL-1.0", CDDL_1_0);
        spdxToRegex.put("CDDL-1.1", CDDL_1_1);
        spdxToRegex.put("ICU", ICU);
        spdxToRegex.put("MPL-1.1", MOZILLA_1_1);
        spdxToRegex.put("MPL-2.0", MOZILLA_2_0);

        return spdxToRegex;
    }
}
