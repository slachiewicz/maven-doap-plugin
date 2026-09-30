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
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.maven.api.model.Contributor;
import org.apache.maven.api.model.Developer;
import org.apache.maven.api.model.License;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.plugin.testing.stubs.ProjectStub;
import org.codehaus.plexus.util.xml.PrettyPrintXMLWriter;
import org.codehaus.plexus.util.xml.XMLWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test {@link DoapUtil} class.
 *
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 */
class DoapUtilTest {

    /**
     * Test method for {@link DoapUtil#writeElement(XMLWriter, String, String, String)}.
     *
     * @throws Exception if any
     */
    @Test
    void testWriteElement() throws Exception {
        StringWriter w = new StringWriter();
        XMLWriter writer = new PrettyPrintXMLWriter(w);
        DoapUtil.writeElement(writer, null, "name", "value");
        w.close();
        assertEquals("<name>value</name>", w.toString());

        w = new StringWriter();
        writer = new PrettyPrintXMLWriter(w);
        try {
            DoapUtil.writeElement(writer, null, null, null);
            fail("Null not catched");
        } catch (IllegalArgumentException e) {
            assertTrue(true, "IllegalArgumentException catched");
        } finally {
            w.close();
        }
    }

    /**
     * Test method for {@link DoapUtil#writeRdfResourceElement(XMLWriter, String, String, String)}.
     *
     * @throws Exception if any
     */
    @Test
    void testWriteRdfResourceElement() throws Exception {
        StringWriter w = new StringWriter();
        XMLWriter writer = new PrettyPrintXMLWriter(w);
        DoapUtil.writeRdfResourceElement(writer, null, "name", "value");
        w.close();
        assertEquals("<name " + DoapUtil.RDF_RESOURCE + "=\"value\"/>", w.toString());

        w = new StringWriter();
        writer = new PrettyPrintXMLWriter(w);
        try {
            DoapUtil.writeRdfResourceElement(writer, null, null, null);
            fail("Null not catched");
        } catch (IllegalArgumentException e) {
            assertTrue(true, "IllegalArgumentException catched");
        } finally {
            w.close();
        }
    }

    /**
     * Test method for:
     * {@link DoapUtil#getContributorsWithDeveloperRole(List)}
     * {@link DoapUtil#getContributorsWithDocumenterRole(List)}
     * {@link DoapUtil#getContributorsWithHelperRole(List)}
     * {@link DoapUtil#getContributorsWithMaintainerRole(List)}
     * {@link DoapUtil#getContributorsWithTesterRole(List)}
     * {@link DoapUtil#getContributorsWithTranslatorRole(List)}
     * {@link DoapUtil#getContributorsWithUnknownRole(List)}
     *
     */
    @Test
    void testDevelopersOrContributorsByDoapRoles() {

        List<Contributor> developersOrContributors = new ArrayList<>();

        // One role
        Developer dev = Developer.newBuilder()
                .id("dev1")
                .roles(Arrays.asList("maintainer"))
                .build();

        developersOrContributors.add(dev);

        assertTrue(DoapUtil.getContributorsWithDeveloperRole(developersOrContributors)
                .isEmpty());
        assertTrue(DoapUtil.getContributorsWithDocumenterRole(developersOrContributors)
                .isEmpty());
        assertTrue(
                DoapUtil.getContributorsWithHelperRole(developersOrContributors).isEmpty());
        assertFalse(DoapUtil.getContributorsWithMaintainerRole(developersOrContributors)
                .isEmpty());
        assertTrue(
                DoapUtil.getContributorsWithTesterRole(developersOrContributors).isEmpty());
        assertTrue(DoapUtil.getContributorsWithTranslatorRole(developersOrContributors)
                .isEmpty());
        assertTrue(DoapUtil.getContributorsWithUnknownRole(developersOrContributors)
                .isEmpty());

        // Several roles
        developersOrContributors.clear();

        dev = Developer.newBuilder()
                .id("dev1")
                .roles(Arrays.asList(" MAINTAINER", "tesTER ", "blabla", "translato r"))
                .build();

        developersOrContributors.add(dev);

        assertTrue(DoapUtil.getContributorsWithDeveloperRole(developersOrContributors)
                .isEmpty());
        assertTrue(DoapUtil.getContributorsWithDocumenterRole(developersOrContributors)
                .isEmpty());
        assertTrue(
                DoapUtil.getContributorsWithHelperRole(developersOrContributors).isEmpty());
        assertFalse(DoapUtil.getContributorsWithMaintainerRole(developersOrContributors)
                .isEmpty());
        assertFalse(
                DoapUtil.getContributorsWithTesterRole(developersOrContributors).isEmpty());
        assertTrue(DoapUtil.getContributorsWithTranslatorRole(developersOrContributors)
                .isEmpty());
        assertFalse(DoapUtil.getContributorsWithUnknownRole(developersOrContributors)
                .isEmpty());

        // Skip emeritus role
        developersOrContributors.clear();

        dev = Developer.newBuilder()
                .id("dev1")
                .roles(Arrays.asList("maintainer", "unknown"))
                .build();

        developersOrContributors.add(dev);

        int sizeBeforeEmeritus = DoapUtil.getContributorsWithUnknownRole(developersOrContributors)
                .size();
        developersOrContributors.clear();
        developersOrContributors.add(dev.with()
                .roles(Arrays.asList("maintainer", "unknown", " Emeritus"))
                .build());

        assertEquals(
                DoapUtil.getContributorsWithUnknownRole(developersOrContributors)
                        .size(),
                sizeBeforeEmeritus);
    }

    /**
     * Test method for:
     * {@link DoapUtil#validate(java.io.File)}
     *
     */
    @Test
    void testValidate() {
        File doapFile = new File("src/test/resources/generated-doap-1.0.rdf");
        assertFalse(DoapUtil.validate(doapFile).isEmpty());
    }

    /**
     * Test method for:
     * {@link DoapUtil#fetchURL(org.apache.maven.api.settings.Settings, java.net.URL)}
     *
     */
    @Test
    void testFetchURLWithNullURL() {
        assertThrows(NullPointerException.class, () -> DoapUtil.fetchURL(null, null));
    }

    /**
     * Test method for:
     * {@link DoapUtil#interpolate(String, org.apache.maven.api.Project, org.apache.maven.api.settings.Settings)}
     *
     */
    @Test
    void testInterpolate() {
        License license =
                License.newBuilder().name("licenseName").url("licenseUrl").build();

        List<Developer> developers = new ArrayList<>();
        developers.add(Developer.newBuilder().id("id1").name("developerName1").build());
        developers.add(Developer.newBuilder().id("id1").name("developerName2").build());

        ProjectStub project = new ProjectStub();
        project.setModel(Model.newBuilder()
                .name("projectName")
                .description("projectDescription")
                .licenses(Collections.singletonList(license))
                .developers(developers)
                .properties(Collections.singletonMap("myKey", "myValue"))
                .build());

        assertEquals("projectName", DoapUtil.interpolate("${project.name}", project, null));
        assertEquals("my name is projectName", DoapUtil.interpolate("my name is ${project.name}", project, null));
        assertEquals(
                "my name is ${project.invalid}", DoapUtil.interpolate("my name is ${project.invalid}", project, null));
        assertEquals("projectDescription", DoapUtil.interpolate("${pom.description}", project, null));
        assertNull(DoapUtil.interpolate("${project.licenses.name}", project, null));
        assertEquals("licenseName", DoapUtil.interpolate("${project.licenses[0].name}", project, null));
        assertNull(DoapUtil.interpolate("${project.licenses[1].name}", project, null));
        assertNotNull(DoapUtil.interpolate("${project.developers}", project, null));
        assertEquals("developerName1", DoapUtil.interpolate("${project.developers[0].name}", project, null));
        assertEquals("developerName2", DoapUtil.interpolate("${project.developers[1].name}", project, null));
        assertEquals("myValue", DoapUtil.interpolate("${myKey}", project, null));
    }
}
