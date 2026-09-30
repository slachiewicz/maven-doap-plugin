/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugin.doap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;

import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.testing.Basedir;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoParameter;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.api.plugin.testing.stubs.ProjectStub;
import org.apache.maven.api.services.ProjectBuilder;
import org.apache.maven.api.services.ProjectBuilderResult;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.Transport;
import org.apache.maven.api.services.TransportProvider;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.doap.options.DoapArtifact;
import org.apache.maven.plugin.doap.options.DoapOptions;
import org.codehaus.plexus.util.StringUtils;
import org.junit.jupiter.api.Test;

import static org.apache.maven.api.plugin.testing.MojoExtension.getBasedir;
import static org.apache.maven.api.plugin.testing.MojoExtension.getVariableValueFromObject;
import static org.apache.maven.api.plugin.testing.MojoExtension.setVariableValueToObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test {@link DoapMojo} class.
 *
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 */
@MojoTest
class DoapMojoTest {

    @Inject
    private Project project;

    /**
     * Verify the generation of a pure DOAP file.
     *
     * @throws Exception if any
     */
    @Test
    @InjectMojo(goal = "generate", pom = "doap-configuration-plugin-config.xml")
    @Basedir("${basedir}/target/test-classes/unit/doap-configuration/")
    void testGeneratedDoap(DoapMojo mojo) throws Exception {
        MavenXpp3Reader pomReader = new MavenXpp3Reader();
        try (InputStream in = Files.newInputStream(Paths.get(getBasedir(), "doap-configuration-plugin-config.xml"))) {
            ((ProjectStub) project).setMavenModel(pomReader.read(in));
        }
        setVariableValueToObject(mojo, "about", project.getModel().getUrl());

        mojo.execute();

        File doapFile = new File(getBasedir(), "target/doap-configuration.rdf");
        assertTrue(doapFile.exists(), "Doap File was not generated!");

        String readed = readFile(doapFile);

        // Validate

        // Pure DOAP
        assertTrue(readed.contains("<rdf:RDF xml:lang=\"en\" xmlns=\"http://usefulinc.com/ns/doap#\" "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                + "xmlns:foaf=\"http://xmlns.com/foaf/0.1/\">"));
        assertTrue(readed.contains("<Project rdf:about=\"" + project.getModel().getUrl() + "\">"));
        assertTrue(readed.contains("<description xml:lang=\"en\">Test the DOAP plugin</description>"));
        assertTrue(readed.contains("<shortdesc xml:lang=\"en\">Test the DOAP plugin</shortdesc>"));
        assertTrue(
                readed.contains("<homepage rdf:resource=\"" + project.getModel().getUrl() + "\"/>"));
        assertTrue(readed.contains("<category>library</category>"));
        assertTrue(readed.contains("<created>2008-01-01</created>"));
        assertTrue(readed.contains("<name>" + project.getModel().getName() + "</name>"));
        assertTrue(readed.contains("<download-page rdf:resource=\"http://foo.org/download.html\"/>"));
        assertTrue(readed.contains("<programming-language>Java</programming-language>"));
        assertTrue(readed.contains("<bug-database rdf:resource=\"http://jira.codehaus.org/browse/MDOAPTEST\"/>"));
        assertTrue(readed.contains("<license rdf:resource=\"http://www.apache.org/licenses/LICENSE-2.0.txt\"/>"));
        assertTrue(readed.contains("<SVNRepository>"));
        assertTrue(
                readed.contains(
                        "<location rdf:resource=\"http://svn.foo.org/repos/asf/maven/plugins/trunk/maven-doap-plugin/src/test/resources/unit/doap-configuration\"/>"));
        assertTrue(
                readed.contains(
                        "<browse rdf:resource=\"http://svn.foo.org/viewvc/maven/plugins/trunk/maven-doap-plugin/src/test/resources/unit/doap-configuration\"/>"));
        assertTrue(
                readed.contains(
                        "<location rdf:resource=\"https://svn.foo.org/repos/asf/maven/plugins/trunk/maven-doap-plugin/src/test/resources/unit/doap-configuration\"/>"));

        // conf
        assertTrue(readed.contains("<audience>developers</audience>"));
        assertTrue(readed.contains("<blog rdf:resource=\"http://myblog.foo.org\"/>"));
        assertTrue(readed.contains("<implements>JSR-foo</implements>"));
        assertTrue(readed.contains("<language>en</language>"));
        assertTrue(readed.contains("<language>fr</language>"));
        assertTrue(readed.contains("<old-homepage rdf:resource=\"http://old.foo.org\"/>"));
        assertTrue(readed.contains("<os>windows</os>"));
        assertTrue(readed.contains("<os>linux</os>"));
        assertTrue(readed.contains("<os>mac</os>"));
        assertTrue(readed.contains("<platform>java</platform>"));
        assertTrue(readed.contains("<platform>firefox</platform>"));
        assertTrue(readed.contains(
                "<screenshots rdf:resource=\"" + project.getModel().getUrl() + "/screenshots.html\"/>"));
        assertTrue(readed.contains("<service-endpoint rdf:resource=\"http://webservice.foo.org\"/>"));
        assertTrue(readed.contains("<wiki rdf:resource=\"http://wiki.foo.org\"/>"));

        // ASF ext
        assertFalse(readed.contains(
                "<asfext:pmc rdf:resource=\"" + project.getModel().getUrl() + "\"/>"));
        assertFalse(readed.contains("<asfext:name>" + project.getModel().getName() + "</name>"));

        // Developers and Organizations
        assertTrue(readed.contains("<maintainer>"));
        assertTrue(readed.contains("<foaf:Person rdf:nodeID=\"b"));
        assertTrue(readed.contains("<foaf:name>Jane Doe</foaf:name>"));
        assertTrue(readed.contains("<foaf:Organization>"));
        assertTrue(readed.contains("<foaf:homepage rdf:resource=\"http://www.example.org\"/>"));
        assertTrue(readed.contains("<foaf:member rdf:nodeID=\"b"));
    }

    /**
     * @throws Exception if any
     */
    @Test
    @InjectMojo(goal = "generate", pom = "doap-configuration-plugin-config.xml")
    @MojoParameter(name = "lang", value = "foo")
    @Basedir("${basedir}/target/test-classes/unit/doap-configuration/")
    void testLangParameter(DoapMojo mojo) throws Exception {
        try {
            mojo.execute();
            fail("No lang checked");
        } catch (MojoException ex) {
            assertEquals(
                    "The <doapOptions><lang>foo</lang></doapOptions> parameter is not a valid ISO language.",
                    ex.getMessage());
        }
    }

    /**
     * @throws Exception if any
     */
    @Test
    @InjectMojo(goal = "generate", pom = "doap-configuration-plugin-config.xml")
    @MojoParameter(name = "about", value = "http://foo/about")
    @Basedir("${basedir}/target/test-classes/unit/doap-configuration/")
    void testAboutParameter(DoapMojo mojo) throws Exception {

        mojo.execute();

        File doapFile = new File(getBasedir(), "target/doap-configuration.rdf");
        assertTrue(doapFile.exists(), "Doap File was not generated!");

        String readed = readFile(doapFile);
        assertTrue(readed.contains("<Project rdf:about=\"http://foo/about\">"));
    }

    /**
     * Verify the generation of a DOAP file from an artifact.
     *
     * @throws Exception if any
     */
    @Test
    @InjectMojo(goal = "generate", pom = "doap-configuration-plugin-config.xml")
    @Basedir("${basedir}/target/test-classes/unit/doap-configuration/")
    void testGeneratedDoapArtifact(DoapMojo mojo) throws Exception {
        DoapOptions doapOptions = (DoapOptions) getVariableValueFromObject(mojo, "doapOptions");
        doapOptions.setDescription("Common Utilities");
        doapOptions.setShortdesc("Common Utilities");
        doapOptions.setDownloadPage("http://plexus.codehaus.org/download-binaries.html");
        setVariableValueToObject(mojo, "doapOptions", doapOptions);

        DoapArtifact artifact = new DoapArtifact();
        artifact.setGroupId("org.codehaus.plexus");
        artifact.setArtifactId("plexus-utils");
        artifact.setVersion("1.5.5");
        setVariableValueToObject(mojo, "artifact", artifact);
        stubArtifactResolution(mojo, "plexus-utils-1.5.5.pom");

        mojo.execute();

        File doapFile = new File(getBasedir(), "target/doap_plexus-utils.rdf");
        assertTrue(doapFile.exists(), "Doap File was not generated!");

        String readed = readFile(doapFile);

        // Validate

        // Pure DOAP
        assertTrue(readed.contains("<rdf:RDF xml:lang=\"en\" xmlns=\"http://usefulinc.com/ns/doap#\" "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                + "xmlns:foaf=\"http://xmlns.com/foaf/0.1/\">"));
        assertTrue(readed.contains("<Project rdf:about=\"http://plexus.codehaus.org/plexus-utils\">"));
        assertTrue(readed.contains("<name>Plexus Common Utilities</name>"));
        assertTrue(readed.contains("<description xml:lang=\"en\">Common Utilities</description>"));
        assertTrue(readed.contains("<shortdesc xml:lang=\"en\">Common Utilities</shortdesc>"));
        assertTrue(readed.contains("<created>2001-01-01</created>"));
        assertTrue(
                readed.contains("<download-page rdf:resource=\"http://plexus.codehaus.org/download-binaries.html\"/>"));
        assertTrue(readed.contains("<programming-language>Java</programming-language>"));
        assertTrue(readed.contains("<bug-database rdf:resource=\"http://jira.codehaus.org/browse/PLXUTILS\"/>"));
        assertTrue(readed.contains("<license rdf:resource=\"http://www.apache.org/licenses/LICENSE-2.0.txt\"/>"));
        assertTrue(readed.contains("<SVNRepository>"));
        assertTrue(readed.contains(
                "<location rdf:resource=\"http://svn.codehaus.org/plexus/plexus-utils/tags/plexus-utils-1.5.5\"/>"));
        assertTrue(
                readed.contains(
                        "<browse rdf:resource=\"http://fisheye.codehaus.org/browse/plexus/plexus-utils/tags/plexus-utils-1.5.5\"/>"));

        // conf
        assertTrue(readed.contains("<audience>developers</audience>"));
        assertTrue(readed.contains("<blog rdf:resource=\"http://myblog.foo.org\"/>"));
        assertTrue(readed.contains("<implements>JSR-foo</implements>"));
        assertTrue(readed.contains("<language>en</language>"));
        assertTrue(readed.contains("<language>fr</language>"));
        assertTrue(readed.contains("<old-homepage rdf:resource=\"http://old.foo.org\"/>"));
        assertTrue(readed.contains("<os>windows</os>"));
        assertTrue(readed.contains("<os>linux</os>"));
        assertTrue(readed.contains("<os>mac</os>"));
        assertTrue(readed.contains("<platform>java</platform>"));
        assertTrue(readed.contains(
                "<screenshots rdf:resource=\"http://plexus.codehaus.org/plexus-utils/screenshots.html\"/>"));
        assertTrue(readed.contains("<service-endpoint rdf:resource=\"http://webservice.foo.org\"/>"));
        assertTrue(readed.contains("<wiki rdf:resource=\"http://wiki.foo.org\"/>"));
    }

    /**
     * Verify the generation of a DOAP file from a minimalist artifact.
     *
     * @throws Exception if any
     */
    @Test
    @InjectMojo(goal = "generate", pom = "doap-configuration-plugin-config.xml")
    @Basedir("${basedir}/target/test-classes/unit/doap-configuration/")
    void testGeneratedDoapArtifactMinimalist(DoapMojo mojo) throws Exception {
        DoapOptions doapOptions = new DoapOptions();
        doapOptions.setName("XStream");
        doapOptions.setDescription("XStream is a simple library to serialize objects to XML and back again.");
        doapOptions.setShortdesc("XML Serializer");
        doapOptions.setHomepage("http://xstream.codehaus.org/");
        doapOptions.setDownloadPage("http://xstream.codehaus.org/download.html");
        doapOptions.setBugDatabase("http://jira.codehaus.org/browse/XSTR");
        doapOptions.setLicense("http://xstream.codehaus.org/license.html");
        doapOptions.setScmDeveloper("http://svn.codehaus.org/xstream/trunk/xstream");
        doapOptions.setMailingList("http://xstream.codehaus.org/list-user.html");
        doapOptions.setCreated("2000-01-01");
        setVariableValueToObject(mojo, "doapOptions", doapOptions);

        DoapArtifact artifact = new DoapArtifact();
        artifact.setGroupId("xstream");
        artifact.setArtifactId("xstream");
        artifact.setVersion("1.1");
        setVariableValueToObject(mojo, "artifact", artifact);
        stubArtifactResolution(mojo, "xstream-1.1.pom");

        mojo.execute();

        File doapFile = new File(getBasedir(), "target/doap_xstream.rdf");
        assertTrue(doapFile.exists(), "Doap File was not generated!");

        String readed = readFile(doapFile);

        // Validate

        // Pure DOAP
        assertTrue(readed.contains("<rdf:RDF xml:lang=\"en\" xmlns=\"http://usefulinc.com/ns/doap#\" "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                + "xmlns:foaf=\"http://xmlns.com/foaf/0.1/\">"));
        assertTrue(readed.contains("<Project>"));
        assertTrue(readed.contains("<name>XStream</name>"));
        assertTrue(
                readed.contains(
                        "<description xml:lang=\"en\">XStream is a simple library to serialize objects to XML and back again.</description>"));
        assertTrue(readed.contains("<shortdesc xml:lang=\"en\">XML Serializer</shortdesc>"));
        assertTrue(readed.contains("<created>2000-01-01</created>"));
        assertTrue(readed.contains("<download-page rdf:resource=\"http://xstream.codehaus.org/download.html\"/>"));
        assertTrue(readed.contains("<programming-language>Java</programming-language>"));
        assertTrue(readed.contains("<bug-database rdf:resource=\"http://jira.codehaus.org/browse/XSTR\"/>"));
        assertTrue(readed.contains("<license rdf:resource=\"http://xstream.codehaus.org/license.html\"/>"));
        assertTrue(readed.contains("<Repository>"));
        assertTrue(readed.contains("<location rdf:resource=\"http://svn.codehaus.org/xstream/trunk/xstream\"/>"));
        assertTrue(readed.contains("<mailing-list rdf:resource=\"http://xstream.codehaus.org/list-user.html\"/>"));

        // conf
        assertFalse(readed.contains("<audience>"));
        assertFalse(readed.contains("<blog rdf:resource="));
        assertFalse(readed.contains("<implements>"));
        assertFalse(readed.contains("<language>"));
        assertFalse(readed.contains("<old-homepage rdf:resource="));
        assertFalse(readed.contains("<os>"));
        assertFalse(readed.contains("<platform>"));
        assertFalse(readed.contains("<screenshots rdf:resource="));
        assertFalse(readed.contains("<service-endpoint rdf:resource="));
        assertFalse(readed.contains("<wiki rdf:resource="));
    }

    /**
     * Verify the generation of a DOAP file with ASF extension.
     *
     * @throws Exception if any
     */
    @Test
    @InjectMojo(goal = "generate", pom = "asf-doap-configuration-plugin-config.xml")
    @Basedir("${basedir}/target/test-classes/unit/asf-doap-configuration/")
    void testGeneratedDoapForASF(DoapMojo mojo) throws Exception {
        MavenXpp3Reader pomReader = new MavenXpp3Reader();
        try (InputStream in =
                Files.newInputStream(Paths.get(getBasedir(), "asf-doap-configuration-plugin-config.xml"))) {
            ((ProjectStub) project).setMavenModel(pomReader.read(in));
        }
        setVariableValueToObject(mojo, "about", project.getModel().getUrl());

        mojo.execute();

        File doapFile = new File(getBasedir(), "target/asf-doap-configuration.rdf");
        assertTrue(doapFile.exists(), "Doap File was not generated!");

        String readed = readFile(doapFile);

        // Validate

        // ASF DOAP
        assertTrue(readed.contains("<rdf:RDF xml:lang=\"en\" xmlns=\"http://usefulinc.com/ns/doap#\" "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                + "xmlns:foaf=\"http://xmlns.com/foaf/0.1/\" "
                + "xmlns:asfext=\"http://projects.apache.org/ns/asfext#\">"));
        if (StringUtils.isNotEmpty(project.getModel().getUrl())) {
            assertTrue(
                    readed.contains("<Project rdf:about=\"" + project.getModel().getUrl() + "\">"));
            assertTrue(readed.contains(
                    "<homepage rdf:resource=\"" + project.getModel().getUrl() + "\"/>"));
        }
        assertTrue(readed.contains("<name>Apache " + project.getModel().getName() + "</name>"));
        assertTrue(readed.contains("<programming-language>Java</programming-language>"));
        assertTrue(readed.contains("<category rdf:resource=\"https://projects.apache.org/category/library\"/>"));
        assertTrue(readed.contains(
                "<mailing-list rdf:resource=\"" + project.getModel().getUrl() + "/mailing-lists.html\"/>"));

        // ASF ext
        assertTrue(readed.contains(
                "<asfext:pmc rdf:resource=\"" + project.getModel().getUrl() + "\"/>"));
        assertTrue(readed.contains("<asfext:name>Apache " + project.getModel().getName() + "</asfext:name>"));
        assertTrue(readed.contains("<asfext:charter>"));
        assertTrue(readed.contains("<asfext:chair>"));
    }

    /**
     * Verify the generation of a DOAP file with extra extension.
     *
     * @throws Exception if any
     */
    @Test
    @InjectMojo(goal = "generate", pom = "doap-extra-configuration-plugin-config.xml")
    @Basedir("${basedir}/target/test-classes/unit/doap-configuration/")
    void testGeneratedExtraDoap(DoapMojo mojo) throws Exception {

        mojo.execute();

        File doapFile = new File(getBasedir(), "target/doap-extra-configuration.rdf");
        assertTrue(doapFile.exists(), "Doap File was not generated!");

        String readed = readFile(doapFile);

        assertTrue(readed.contains("<ciManagement rdf:resource=\"http://ci.foo.org\"/>"));
        assertTrue(readed.contains("<asfext:status>active</asfext:status>"));
        assertTrue(readed.contains("<labs:status>active</labs:status>"));
    }

    /**
     * Verify that, by default, <code>doap:file-release</code> points at the artifact of the project's packaging.
     *
     * @throws Exception if any
     */
    @Test
    @InjectMojo(goal = "generate", pom = "doap-configuration-plugin-config.xml")
    @Basedir("${basedir}/target/test-classes/unit/doap-configuration/")
    void testFileReleaseDefaultsToPackaging(DoapMojo mojo) throws Exception {
        String readed = generateWithReleaseRepository(mojo);

        assertTrue(readed.contains("<revision>1.0</revision>"));
        // the most recent release comes first
        assertTrue(readed.indexOf("<revision>1.0</revision>") < readed.indexOf("<revision>0.9</revision>"));
        assertTrue(readed.contains("<file-release>file:"));
        assertTrue(readed.contains("/resources/test/resources-test/1.0/resources-test-1.0.jar</file-release>"));
        assertTrue(readed.contains("<created>2008-01-01</created>"));
        assertFalse(readed.contains("resources-test-1.0-source-release.zip"));
    }

    /**
     * Verify that <code>fileReleaseClassifier</code> and <code>fileReleaseType</code> redirect
     * <code>doap:file-release</code> to another artifact of the release, here the ASF source-release archive.
     *
     * @throws Exception if any
     */
    @Test
    @InjectMojo(goal = "generate", pom = "doap-configuration-plugin-config.xml")
    @MojoParameter(name = "fileReleaseClassifier", value = "source-release")
    @MojoParameter(name = "fileReleaseType", value = "zip")
    @Basedir("${basedir}/target/test-classes/unit/doap-configuration/")
    void testFileReleaseClassifierAndType(DoapMojo mojo) throws Exception {
        String readed = generateWithReleaseRepository(mojo);

        assertTrue(readed.contains("<revision>1.0</revision>"));
        assertTrue(readed.contains("<file-release>file:"));
        assertTrue(readed.contains(
                "/resources/test/resources-test/1.0/resources-test-1.0-source-release.zip</file-release>"));
        assertFalse(readed.contains("resources-test-1.0.jar"));
    }

    /**
     * Run the mojo against a file-based release repository holding version 1.0 of the test project, as both a jar
     * and a source-release zip, and return the generated DOAP file.
     */
    private String generateWithReleaseRepository(DoapMojo mojo) throws Exception {
        MavenXpp3Reader pomReader = new MavenXpp3Reader();
        try (InputStream in = Files.newInputStream(Paths.get(getBasedir(), "doap-configuration-plugin-config.xml"))) {
            ((ProjectStub) project).setMavenModel(pomReader.read(in));
        }

        Path repository = Paths.get(getBasedir(), "target", "release-repo");
        Path artifactDir = repository.resolve("resources/test/resources-test");
        Files.createDirectories(artifactDir.resolve("1.0"));
        Files.write(
                artifactDir.resolve("maven-metadata.xml"),
                ("<metadata>\n"
                                + "  <groupId>resources.test</groupId>\n"
                                + "  <artifactId>resources-test</artifactId>\n"
                                + "  <versioning>\n"
                                + "    <latest>1.0</latest>\n"
                                + "    <release>1.0</release>\n"
                                + "    <versions>\n"
                                + "      <version>0.9</version>\n"
                                + "      <version>1.0</version>\n"
                                + "    </versions>\n"
                                + "    <lastUpdated>20080101120000</lastUpdated>\n"
                                + "  </versioning>\n"
                                + "</metadata>\n")
                        .getBytes(StandardCharsets.UTF_8));
        // the plugin tells that a release file exists by its checksum file, as the transport cannot ask for less
        for (String file : new String[] {"resources-test-1.0.jar", "resources-test-1.0-source-release.zip"}) {
            Files.write(artifactDir.resolve("1.0/" + file), new byte[0]);
            Files.write(
                    artifactDir.resolve("1.0/" + file + ".sha1"),
                    "da39a3ee5e6b4b0d3255bfef95601890afd80709".getBytes(StandardCharsets.UTF_8));
        }

        // writeReleases() only reads versioning metadata from repositories that serve releases but not snapshots; a
        // repository the project does not declare is taken as one
        RemoteRepository remoteRepository = mock(RemoteRepository.class);
        when(remoteRepository.getId()).thenReturn("releases");
        when(remoteRepository.getUrl())
                .thenReturn(StringUtils.chomp(repository.toUri().toString(), "/"));
        ProjectManager projectManager = mock(ProjectManager.class);
        when(projectManager.getRemoteProjectRepositories(any()))
                .thenReturn(Collections.singletonList(remoteRepository));
        setVariableValueToObject(mojo, "projectManager", projectManager);

        Transport transport = new DirectoryTransport(repository);
        TransportProvider transportProvider = mock(TransportProvider.class);
        when(transportProvider.transport(any(), any())).thenReturn(transport);
        setVariableValueToObject(mojo, "transportProvider", transportProvider);

        Session session = (Session) getVariableValueFromObject(mojo, "session");
        Type type = mock(Type.class);
        when(type.getExtension()).thenReturn("jar");
        when(session.requireType("jar")).thenReturn(type);
        Type zip = mock(Type.class);
        when(zip.getExtension()).thenReturn("zip");
        when(session.requireType("zip")).thenReturn(zip);

        mojo.execute();

        File doapFile = new File(getBasedir(), "target/doap-configuration.rdf");
        assertTrue(doapFile.exists(), "Doap File was not generated!");
        return readFile(doapFile);
    }

    /**
     * Make the artifact parameter resolve to a POM of the test resources instead of one from a remote repository.
     */
    private void stubArtifactResolution(DoapMojo mojo, String pomResource) throws Exception {
        Path pom = Paths.get(getBasedir(), "..", "artifact", pomResource).normalize();

        MavenXpp3Reader pomReader = new MavenXpp3Reader();
        ProjectStub resolved = new ProjectStub();
        try (InputStream in = Files.newInputStream(pom)) {
            resolved.setMavenModel(pomReader.read(in));
        }

        Session session = (Session) getVariableValueFromObject(mojo, "session");
        when(session.createArtifactCoordinates(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mock(ArtifactCoordinates.class));
        DownloadedArtifact downloaded = mock(DownloadedArtifact.class);
        when(downloaded.getPath()).thenReturn(pom);
        when(session.resolveArtifact(any(ArtifactCoordinates.class), anyList())).thenReturn(downloaded);

        ProjectManager projectManager = mock(ProjectManager.class);
        when(projectManager.getRemoteProjectRepositories(any())).thenReturn(Collections.emptyList());
        setVariableValueToObject(mojo, "projectManager", projectManager);

        ProjectBuilderResult result = mock(ProjectBuilderResult.class);
        when(result.getProject()).thenReturn(Optional.of(resolved));
        ProjectBuilder projectBuilder = mock(ProjectBuilder.class);
        when(projectBuilder.build(any(org.apache.maven.api.services.ProjectBuilderRequest.class)))
                .thenReturn(result);
        setVariableValueToObject(mojo, "projectBuilder", projectBuilder);
    }

    /**
     * A transport reading from a directory, the way a file based repository is served.
     */
    private static class DirectoryTransport implements Transport {
        private final Path root;

        DirectoryTransport(Path root) {
            this.root = root;
        }

        @Override
        public boolean get(URI relative, Path target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<byte[]> getBytes(URI relative) {
            Path file = root.resolve(relative.getPath());
            try {
                return Files.exists(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Optional<String> getString(URI relative, java.nio.charset.Charset charset) {
            return getBytes(relative).map(bytes -> new String(bytes, charset));
        }

        @Override
        public void put(Path source, URI relative) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putBytes(byte[] data, URI relative) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putString(String data, java.nio.charset.Charset charset, URI relative) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }

    /**
     * Verify that a POM with empty scm, issueManagement, organization and prerequisites elements still
     * generates a DOAP file.
     *
     * @throws Exception if any
     */
    @Test
    @InjectMojo(goal = "generate", pom = "minimal-plugin-config.xml")
    @Basedir("${basedir}/target/test-classes/unit/minimal/")
    void testGeneratedDoapForMinimalPom(DoapMojo mojo) throws Exception {
        MavenXpp3Reader pomReader = new MavenXpp3Reader();
        try (InputStream in = Files.newInputStream(Paths.get(getBasedir(), "minimal-plugin-config.xml"))) {
            ((ProjectStub) project).setMavenModel(pomReader.read(in));
        }

        mojo.execute();

        File doapFile = new File(getBasedir(), "target/doap_minimal.rdf");
        assertTrue(doapFile.exists(), "Doap File was not generated!");
        assertTrue(readFile(doapFile).contains("<name>minimal</name>"));
    }

    /**
     * Verify that an ASF project fails when the first sentence of the short description is longer than
     * ten words.
     *
     * @throws Exception if any
     */
    @Test
    @InjectMojo(goal = "generate", pom = "asf-doap-configuration-plugin-config.xml")
    @Basedir("${basedir}/target/test-classes/unit/asf-doap-configuration/")
    void testShortDescTooLongFailsForASF(DoapMojo mojo) throws Exception {
        Log log = mock(Log.class);
        setVariableValueToObject(mojo, "log", log);
        MavenXpp3Reader pomReader = new MavenXpp3Reader();
        try (InputStream in =
                Files.newInputStream(Paths.get(getBasedir(), "asf-doap-configuration-plugin-config.xml"))) {
            ((ProjectStub) project).setMavenModel(pomReader.read(in));
        }
        DoapOptions doapOptions = (DoapOptions) getVariableValueFromObject(mojo, "doapOptions");
        // the length check applies when the short description falls back to the description
        String description = "Tests the check of a simple plugin Tests the check of a simple plugin.";
        doapOptions.setDescription(description);
        doapOptions.setShortdesc(description);
        setVariableValueToObject(mojo, "doapOptions", doapOptions);

        MojoException e = assertThrows(MojoException.class, mojo::execute);

        assertEquals("The generated DOAP doesn't respect ASF rules, see above.", e.getMessage());
        verify(log)
                .error("The <doapOptions><shortdesc/></doapOptions> first sentence is too long maximum words "
                        + "number is 10.");
    }

    /**
     * Verify that a Git connection is written as a DOAP Git repository with a plain URL as its location.
     *
     * @throws Exception if any
     */
    @Test
    @InjectMojo(goal = "generate", pom = "doap-configuration-plugin-config.xml")
    @Basedir("${basedir}/target/test-classes/unit/doap-configuration/")
    void testGitRepository(DoapMojo mojo) throws Exception {
        MavenXpp3Reader pomReader = new MavenXpp3Reader();
        Model model;
        try (InputStream in = Files.newInputStream(Paths.get(getBasedir(), "doap-configuration-plugin-config.xml"))) {
            model = pomReader.read(in);
        }
        model.getScm().setConnection("scm:git:https://git.example.org/doap-plugin.git");
        model.getScm().setDeveloperConnection("scm:git|https://user@git.example.org/doap.git");
        ((ProjectStub) project).setMavenModel(model);
        setVariableValueToObject(mojo, "about", project.getModel().getUrl());

        mojo.execute();

        String readed = readFile(new File(getBasedir(), "target/doap-configuration.rdf"));
        assertFalse(readed.contains("<SVNRepository>"));
        assertTrue(readed.contains("<GitRepository>"));
        assertTrue(readed.contains("<location rdf:resource=\"https://git.example.org/doap-plugin.git\"/>"));
        assertTrue(readed.contains("<location rdf:resource=\"https://git.example.org/doap.git\"/>"));
    }

    private String readFile(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
