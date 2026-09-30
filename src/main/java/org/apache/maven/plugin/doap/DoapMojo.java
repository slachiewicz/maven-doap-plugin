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

import javax.xml.stream.XMLStreamException;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;

import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.metadata.Metadata;
import org.apache.maven.api.model.Contributor;
import org.apache.maven.api.model.Developer;
import org.apache.maven.api.model.License;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ArtifactResolverException;
import org.apache.maven.api.services.ProjectBuilder;
import org.apache.maven.api.services.ProjectBuilderException;
import org.apache.maven.api.services.ProjectBuilderRequest;
import org.apache.maven.api.services.ProjectBuilderResult;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.Transport;
import org.apache.maven.api.services.TransportProvider;
import org.apache.maven.metadata.v4.MetadataStaxReader;
import org.apache.maven.plugin.doap.options.ASFExtOptions;
import org.apache.maven.plugin.doap.options.ASFExtOptionsUtil;
import org.apache.maven.plugin.doap.options.DoapArtifact;
import org.apache.maven.plugin.doap.options.DoapOptions;
import org.apache.maven.plugin.doap.options.ExtOptions;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.PrettyPrintXMLWriter;
import org.codehaus.plexus.util.xml.XMLWriter;
import org.codehaus.plexus.util.xml.XmlStreamWriter;

/**
 * <p>
 * Generate a <a href="https://github.com/ewilderj/doap">Description of a Project (DOAP)</a> file from the main information
 * found in a POM.
 * </p>
 * <b>Note</b>: The generated file is tailored for use by projects at
 * <a href="http://projects.apache.org/doap.html">Apache</a>.
 *
 * @author Jason van Zyl
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @since 1.0-beta-1
 */
@Mojo(name = "generate")
public class DoapMojo implements org.apache.maven.api.plugin.Mojo {
    /**
     * UTC Time Zone.
     */
    private static final TimeZone UTC_TIME_ZONE = TimeZone.getTimeZone("UTC");

    /**
     * Date format for <lastUpdated/> tag in the repository metadata, i.e.: yyyyMMddHHmmss.
     */
    private static final DateFormat REPOSITORY_DATE_FORMAT;

    /**
     * Date format for DOAP file, i.e. ISO-8601 YYYY-MM-DD.
     */
    private static final DateFormat DOAP_DATE_FORMAT;

    static {
        REPOSITORY_DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH);
        REPOSITORY_DATE_FORMAT.setTimeZone(UTC_TIME_ZONE);

        DOAP_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
        DOAP_DATE_FORMAT.setTimeZone(UTC_TIME_ZONE);
    }

    // ----------------------------------------------------------------------
    // Mojo components
    // ----------------------------------------------------------------------

    /**
     * Logger.
     */
    @Inject
    private Log log;

    /**
     * The current build session.
     */
    @Inject
    private Session session;

    /**
     * The POM from which information will be extracted to create a DOAP file.
     */
    @Inject
    private Project project;

    /**
     * Project builder, used to read the POM of the <code>artifact</code> parameter.
     *
     * @since 1.1
     */
    private ProjectBuilder projectBuilder;

    /**
     * Gives access to the remote repositories of the project. Not bound for injection in Maven 4.0.0-rc-7, so it is
     * looked up from the session on first use. The project builder and the transport provider are looked up the
     * same way, which also keeps the plugin usable in the test harness, where their implementations cannot be built.
     */
    private ProjectManager projectManager;

    /**
     * Reads <code>maven-metadata.xml</code> and release artifacts from remote repositories.
     */
    private TransportProvider transportProvider;

    /**
     * The name of the DOAP file that will be generated.
     */
    @Parameter(property = "doapFile", defaultValue = "doap_${project.artifactId}.rdf", required = true)
    private String doapFile;

    /**
     * The output directory of the DOAP file that will be generated.
     *
     * @since 1.1
     */
    @Parameter(defaultValue = "${project.reporting.outputDirectory}")
    private String outputDirectory;

    /**
     * The classifier of the artifact to reference as <code>doap:file-release</code>, e.g.
     * <code>source-release</code> for the ASF source-release archive. When not set, the release artifact is
     * resolved without a classifier, as before.
     *
     * @since 3.0.0-M2
     */
    @Parameter(property = "fileReleaseClassifier")
    private String fileReleaseClassifier;

    /**
     * The type of the artifact to reference as <code>doap:file-release</code>, e.g. <code>zip</code> for the
     * ASF source-release archive. Defaults to the project's packaging.
     *
     * @since 3.0.0-M2
     */
    @Parameter(property = "fileReleaseType")
    private String fileReleaseType;

    // ----------------------------------------------------------------------
    // Doap options
    // ----------------------------------------------------------------------

    /**
     * Specific DOAP parameters, i.e. options that POM doesn't have any notions. <br/>
     * Example:
     * <p/>
     * <pre>
     * &lt;doapOptions&gt;
     * &nbsp;&nbsp;&lt;programmingLanguage&gt;java&lt;/programmingLanguage&gt;
     * &lt;/doapOptions&gt;
     * </pre>
     * <p/>
     * <br/>
     * See <a href="./apidocs/org/apache/maven/plugin/doap/options/DoapOptions.html">Javadoc</a> <br/>
     *
     * @see <a href="http://usefulinc.com/ns/doap#">http://usefulinc.com/ns/doap#</a>
     * @since 1.0
     */
    @Parameter(property = "doapOptions")
    private DoapOptions doapOptions;

    /**
     * Specific ASF extensions parameters, i.e. options that POM doesn't have any notions but required by ASF DOAP
     * requirements. <br/>
     * Example:
     * <p/>
     * <pre>
     * &lt;asfExtOptions&gt;
     * &nbsp;&nbsp;&lt;included&gt;true&lt;/included&gt;
     * &nbsp;&nbsp;&lt;charter&gt;The mission of the Apache XXX project is to create and maintain software
     * &nbsp;&nbsp;libraries that provide ...&lt;/charter&gt;
     * &nbsp;&nbsp;...
     * &lt;/asfExtOptions&gt;
     * </pre>
     * <p/>
     * <b>Note</b>: By default, <code>&lt;asfExtOptions&gt;&lt;included/&gt;&lt;/asfExtOptions&gt;</code> will be
     * automatically set to <code>true</code> if the project is hosted at ASF. <br/>
     * See <a href="./apidocs/org/apache/maven/plugin/doap/options/ASFExtOptions.html">Javadoc</a> <br/>
     *
     * @see <a href="https://projects.apache.org/ns/asfext#">
     *      https://projects.apache.org/ns/asfext#</a>
     * @see <a href="http://projects.apache.org/docs/pmc.html">http://projects.apache.org/docs/pmc.html</a>
     * @see <a href="http://projects.apache.org/docs/standards.html">http://projects.apache.org/docs/standards.html</a>
     * @see ASFExtOptionsUtil#isASFProject(Project)
     * @since 1.0
     */
    @Parameter(property = "asfExtOptions")
    private ASFExtOptions asfExtOptions;

    /**
     * The value for the <code>xml:lang</code> attribute used by the <code>&lt;rdf:RDF/&gt;<code>,
     * <code>&lt;description/&gt;</code> and <code>&lt;shortdesc/&gt;</code> elements. <br/>
     * POM doesn't have any notions about language. <br/>
     * See <a href="http://www.w3.org/TR/REC-xml/#sec-lang-tag">http://www.w3.org/TR/REC-xml/#sec-lang-tag</a> <br/>
     *
     * @since 1.0
     */
    @Parameter(property = "lang", defaultValue = "en", required = true)
    private String lang;

    /**
     * The <code>about</code> URI-reference which should be displayed in the DOAP file. Example:
     * <p/>
     * <pre>
     * &lt;rdf:RDF&gt;
     * &nbsp;&nbsp;&lt;Project rdf:about="http://maven.apache.org/"&gt;
     * &nbsp;&nbsp;...
     * &nbsp;&nbsp;&lt;/Project&gt;
     * &lt;/rdf:RDF&gt;
     * </pre>
     * <p/>
     * See <a href="http://www.w3.org/TR/1999/REC-rdf-syntax-19990222/#aboutAttr">
     * http://www.w3.org/TR/1999/REC-rdf-syntax-19990222/#aboutAttr</a> <br/>
     *
     * @since 1.0
     */
    @Parameter(property = "about", defaultValue = "${project.url}")
    private String about;

    /**
     * Flag to validate the generated DOAP.
     *
     * @since 1.1
     */
    @Parameter(defaultValue = "true")
    private boolean validate;

    /**
     * An artifact to generate the DOAP file against. <br/>
     * Example:
     * <p/>
     * <pre>
     * &lt;artifact&gt;
     * &nbsp;&nbsp;&lt;groupId&gt;given-artifact-groupId&lt;/groupId&gt;
     * &nbsp;&nbsp;&lt;artifactId&gt;given-artifact-artifactId&lt;/artifactId&gt;
     * &nbsp;&nbsp;&lt;version&gt;given-artifact-version&lt;/version&gt;
     * &lt;/artifact&gt;
     * </pre>
     * <p/>
     * <br/>
     * See <a href="./apidocs/org/apache/maven/plugin/doap/options/DaopArtifact.html">Javadoc</a> <br/>
     *
     * @since 1.1
     */
    @Parameter
    private DoapArtifact artifact;

    /**
     * Specifies whether the DOAP generation should be skipped.
     *
     * @since 1.1
     */
    @Parameter(property = "maven.doap.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Extensions parameters. <br/>
     * Example:
     * <p/>
     * <pre>
     * &lt;extOptions&gt;
     * &nbsp;&lt;extOption&gt;
     * &nbsp;&nbsp;&nbsp;&lt;xmlnsPrefix&gt;labs&lt;/xmlnsPrefix&gt;
     * &nbsp;&nbsp;&nbsp;&lt;xmlnsNamespaceURI&gt;http://labs.apache.org/doap-ext/1.0#&lt;/xmlnsNamespaceURI&gt;
     * &nbsp;&nbsp;&nbsp;&lt;extensions&gt;
     * &nbsp;&nbsp;&nbsp;&nbsp;&lt;status&gt;active&lt;/status&gt;
     * &nbsp;&nbsp;&nbsp;&lt;/extensions&gt;
     * &nbsp;&lt;/extOption&gt;
     * &lt;/extOptions&gt;
     * </pre>
     * <p/>
     * See <a href="./apidocs/org/apache/maven/plugin/doap/options/ExtOptions.html">Javadoc</a> <br/>
     *
     * @since 1.1
     */
    @Parameter(property = "extOptions")
    private ExtOptions[] extOptions;

    /**
     * All warn/error messages for the user.
     *
     * @since 1.1
     */
    private UserMessages messages;

    private ContributorWriter contributorWriter;

    private AsfExtWriter asfExtWriter;

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    /**
     * The output directory as a file. Maven 3 always gave <code>${project.reporting.outputDirectory}</code> the value
     * <code>${project.build.directory}/site</code>; Maven 4 leaves it empty unless the POM sets it, so that default is
     * applied here.
     */
    private File getOutputDirectory() {
        String directory = outputDirectory;
        if (StringUtils.isEmpty(directory)) {
            directory = project.getBuild().getDirectory() + "/site";
        }
        File outDir = new File(directory);
        if (!outDir.isAbsolute()) {
            outDir = new File(project.getBasedir().toFile(), directory);
        }
        return outDir;
    }

    private Log getLog() {
        return log;
    }

    private ProjectBuilder getProjectBuilder() {
        if (projectBuilder == null) {
            projectBuilder = session.getService(ProjectBuilder.class);
        }
        return projectBuilder;
    }

    private TransportProvider getTransportProvider() {
        if (transportProvider == null) {
            transportProvider = session.getService(TransportProvider.class);
        }
        return transportProvider;
    }

    private ProjectManager getProjectManager() {
        if (projectManager == null) {
            projectManager = session.getService(ProjectManager.class);
        }
        return projectManager;
    }

    @Override
    public void execute() throws MojoException {
        if (skip) {
            getLog().info("Skipping DOAP generation");
            return;
        }

        // single artifact
        if (artifact != null) {
            Project givenProject = getMavenProject(artifact);
            if (givenProject != null) {
                File outDir = getOutputDirectory();
                File outFile = new File(outDir, artifact.getDoapFileName());
                writeDoapFile(givenProject, outFile);
                return;
            }
        }

        // current project
        File outFile = new File(doapFile);
        if (!outFile.isAbsolute()) {
            outFile = new File(project.getBasedir().toFile(), doapFile);
        }
        if (!doapFile.replaceAll("\\\\", "/").contains("/")) {
            outFile = new File(getOutputDirectory(), doapFile);
        }
        writeDoapFile(project, outFile);
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    /**
     * @param artifact not null
     * @return the maven project for the given doap artifact
     * @since 1.1
     */
    private Project getMavenProject(DoapArtifact artifact) {
        if (artifact == null) {
            return null;
        }

        if (StringUtils.isEmpty(artifact.getGroupId())
                || StringUtils.isEmpty(artifact.getArtifactId())
                || StringUtils.isEmpty(artifact.getVersion())) {
            getLog().warn("Missing groupId or artifactId or version in <artifact/> parameter, ignored it.");
            return null;
        }

        getLog().info("Using artifact " + artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                + artifact.getVersion());

        try {
            List<RemoteRepository> repositories = getProjectManager().getRemoteProjectRepositories(project);

            // the v4 ProjectBuilder reads a POM file or source, not coordinates, so resolve the POM first. Build it
            // from
            // the path: a build from a Source returns no Project, since the POM has no base directory.
            Path pom = session.resolveArtifact(
                            session.createArtifactCoordinates(
                                    artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), "pom"),
                            repositories)
                    .getPath();

            ProjectBuilderRequest request = ProjectBuilderRequest.builder()
                    .session(session)
                    .path(pom)
                    .processPlugins(false)
                    .repositories(repositories)
                    .build();

            ProjectBuilderResult result = getProjectBuilder().build(request);
            if (!result.getProject().isPresent()) {
                getLog().error("No project could be built from " + pom + ": " + result.getProblems()
                        + "\nIgnored <artifact/> parameter.");
                return null;
            }
            return result.getProject().get();
        } catch (ArtifactResolverException e) {
            getLog().error("ArtifactResolverException: " + e.getMessage() + "\nIgnored <artifact/> parameter.");
        } catch (ProjectBuilderException e) {
            getLog().error("ProjectBuilderException: " + e.getMessage() + "\nIgnored <artifact/> parameter.");
        }

        return null;
    }

    /**
     * Write a doap file for the given project.
     *
     * @param project    not null
     * @param outputFile not null
     * @since 1.1
     */
    private void writeDoapFile(Project project, File outputFile) throws MojoException {
        // ----------------------------------------------------------------------------
        // Includes ASF extensions
        // ----------------------------------------------------------------------------

        messages = new UserMessages(asfExtOptions);
        contributorWriter = new ContributorWriter(doapOptions, messages);
        asfExtWriter = new AsfExtWriter(doapOptions, asfExtOptions, session.getSettings(), messages, contributorWriter);

        if (!asfExtOptions.isIncluded() && ASFExtOptionsUtil.isASFProject(project)) {
            getLog().info("This project is an ASF project, ASF Extensions to DOAP will be added.");
            asfExtOptions.setIncluded(true);
        }

        // ----------------------------------------------------------------------------
        // setup pretty print xml writer
        // ----------------------------------------------------------------------------

        Writer w;
        try {
            if (!outputFile.getParentFile().exists()) {
                FileUtils.mkdir(outputFile.getParentFile().getAbsolutePath());
            }

            w = new XmlStreamWriter(outputFile);
        } catch (IOException e) {
            throw new MojoException("Error creating DOAP file " + outputFile.getAbsolutePath(), e);
        }

        try {
            doWrite(project, outputFile, w);
        } finally {

            try {
                w.close();
            } catch (IOException e) {
                throw new MojoException("Error when closing the writer.", e);
            }
        }

        if (!messages.getWarnMessages().isEmpty()) {
            for (String warn : messages.getWarnMessages()) {
                getLog().warn(warn);
            }
        }

        if (!messages.getErrorMessages().isEmpty()) {
            getLog().error("");
            for (String error : messages.getErrorMessages()) {
                getLog().error(error);
            }
            getLog().error("");

            if (ASFExtOptionsUtil.isASFProject(project)) {
                getLog().error("For more information about the errors and possible solutions, "
                        + "please read the plugin documentation:");
                getLog().error("http://maven.apache.org/plugins/maven-doap-plugin/usage.html#DOAP_ASF_Configuration");
                throw new MojoException("The generated DOAP doesn't respect ASF rules, see above.");
            }
        }

        if (validate) {
            List<String> errors = DoapUtil.validate(outputFile);
            if (!errors.isEmpty()) {
                getLog().error("");
                for (String error : errors) {
                    getLog().error(error);
                }
                getLog().error("");

                throw new MojoException("Error parsing the generated DOAP file, see above.");
            }
        }
    }
    // CHECKSTYLE_OFF: MethodLength
    private void doWrite(Project project, File outputFile, Writer w) throws MojoException {
        if (asfExtOptions.isIncluded()) {
            getLog().info("Generating an ASF DOAP file " + outputFile.getAbsolutePath());
        } else {
            getLog().info("Generating a pure DOAP file " + outputFile.getAbsolutePath());
        }

        XMLWriter writer = new PrettyPrintXMLWriter(w, project.getModel().getModelEncoding(), null);

        // ----------------------------------------------------------------------------
        // Convert POM to DOAP
        // ----------------------------------------------------------------------------

        DoapUtil.writeHeader(writer);

        // Heading
        DoapUtil.writeStartElement(writer, "rdf", "RDF");
        if (Arrays.binarySearch(Locale.getISOLanguages(), lang) < 0) {
            messages.addMessage(new String[] {"doapOptions", "lang"}, lang, UserMessages.INVALID_ISO_DATE);
            throw new MojoException(messages.getErrorMessages().get(0));
        }
        writer.addAttribute("xml:lang", lang);
        if (StringUtils.isEmpty(doapOptions.getXmlnsNamespaceURI())) {
            messages.addMessage(new String[] {"doapOptions", "xmlnsNamespaceURI"}, null, UserMessages.REQUIRED);
            throw new MojoException(messages.getErrorMessages().get(0));
        }
        writer.addAttribute(
                "xmlns" + (StringUtils.isEmpty(doapOptions.getXmlnsPrefix()) ? "" : ":" + doapOptions.getXmlnsPrefix()),
                doapOptions.getXmlnsNamespaceURI());
        writer.addAttribute("xmlns:rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        writer.addAttribute("xmlns:foaf", "http://xmlns.com/foaf/0.1/");
        if (asfExtOptions.isIncluded()) {
            if (StringUtils.isEmpty(asfExtOptions.getXmlnsPrefix())) {
                messages.addMessage(new String[] {"doapOptions", "xmlnsPrefix"}, null, UserMessages.REQUIRED);
                throw new MojoException(messages.getErrorMessages().get(0));
            }
            if (StringUtils.isEmpty(asfExtOptions.getXmlnsNamespaceURI())) {
                messages.addMessage(new String[] {"doapOptions", "xmlnsNamespaceURI"}, null, UserMessages.REQUIRED);
            }
            writer.addAttribute(
                    "xmlns"
                            + (StringUtils.isEmpty(asfExtOptions.getXmlnsPrefix())
                                    ? ""
                                    : ":" + asfExtOptions.getXmlnsPrefix()),
                    asfExtOptions.getXmlnsNamespaceURI());
        }
        if (extOptions != null
                && extOptions.length > 0
                && !extOptions[0].getExtensions().isEmpty()) {
            for (ExtOptions extOption : extOptions) {
                if (StringUtils.isEmpty(extOption.getXmlnsPrefix())) {
                    messages.addMessage(
                            new String[] {"extOptions", "extOption", "xmlnsPrefix"}, null, UserMessages.REQUIRED);
                    throw new MojoException(messages.getErrorMessages().get(0));
                }
                if (StringUtils.isEmpty(extOption.getXmlnsNamespaceURI())) {
                    messages.addMessage(
                            new String[] {"extOptions", "extOption", "xmlnsNamespaceURI"}, null, UserMessages.REQUIRED);
                    throw new MojoException(messages.getErrorMessages().get(0));
                }
                writer.addAttribute(
                        "xmlns"
                                + (StringUtils.isEmpty(extOption.getXmlnsPrefix())
                                        ? ""
                                        : ":" + extOption.getXmlnsPrefix()),
                        extOption.getXmlnsNamespaceURI());
            }
        }

        // Project
        DoapUtil.writeStartElement(writer, doapOptions.getXmlnsPrefix(), "Project");
        boolean added = false;
        if (artifact != null) {
            String about = project.getModel().getUrl();

            if (about != null && !about.isEmpty()) {
                try {
                    new URL(about);

                    writer.addAttribute("rdf:about", about);
                    added = true;
                } catch (MalformedURLException e) {
                    // ignore
                }
            }

            if (!added) {
                messages.getWarnMessages()
                        .add("The project's url defined from " + artifact.toConfiguration()
                                + " is empty or not a valid URL, using <about/> parameter.");
            }
        }

        if (!added) {
            if (about != null && !about.isEmpty()) {
                try {
                    new URL(about);

                    writer.addAttribute("rdf:about", about);
                } catch (MalformedURLException e) {
                    messages.addMessage(new String[] {"about"}, about, UserMessages.INVALID_URL);
                }
                added = true;
            }
        }

        if (!added) {
            messages.addMessage(new String[] {"about"}, null, UserMessages.RECOMMENDED);
        }

        // name
        writeName(writer, project);

        // description
        writeDescription(writer, project);

        // implements
        writeImplements(writer);

        // Audience
        writeAudience(writer);

        // Vendor
        writeVendor(writer, project);

        // created
        writeCreated(writer, project);

        // homepage and old-homepage
        writeHomepage(writer, project);

        // Blog
        writeBlog(writer);

        // licenses
        writeLicenses(writer, project);

        // programming-language
        writeProgrammingLanguage(writer, project);

        // category
        writeCategory(writer, project);

        // os
        writeOS(writer, project);

        // Plateform
        writePlateform(writer);

        // Language
        writeLanguage(writer);

        // SCM
        writeSourceRepositories(writer, project);

        // bug-database
        writeBugDatabase(writer, project);

        // mailing list
        writeMailingList(writer, project);

        // download-page and download-mirror
        writeDownloadPage(writer, project);

        // screenshots
        writeScreenshots(writer, project);

        // service-endpoint
        writeServiceEndpoint(writer);

        // wiki
        writeWiki(writer, project);

        // Releases
        writeReleases(writer, project);

        // Developers
        List<Developer> developers = project.getModel().getDevelopers();
        contributorWriter.writeContributors(writer, new ArrayList<Contributor>(developers));

        // Contributors
        List<Contributor> contributors = new ArrayList<>(project.getModel().getContributors());
        contributorWriter.writeContributors(writer, contributors);

        // Extra DOAP
        Map<Object, String> map = doapOptions.getExtra();
        DoapUtil.writeExtra(
                writer, project, session.getSettings(), "Extra DOAP vocabulary.", map, doapOptions.getXmlnsPrefix());

        // ASFext
        asfExtWriter.writeASFext(writer, project);

        // Extra extensions
        writeExtensions(writer);

        writer.endElement(); // Project

        writeOrganizations(writer);

        writer.endElement(); // rdf:RDF
    }
    // CHECKSTYLE_OFF: MethodLength
    /**
     * Write DOAP name.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#name">http://usefulinc.com/ns/doap#name</a>
     */
    private void writeName(XMLWriter writer, Project project) {
        String name = DoapUtil.interpolate(doapOptions.getName(), project, session.getSettings());
        if (name == null || name.isEmpty()) {
            messages.addMessage(
                    new String[] {"doapOptions", "name"}, null, UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
            return;
        }

        DoapUtil.writeComment(writer, "A name of something.");
        if (ASFExtOptionsUtil.isASFProject(project)
                && !name.toLowerCase(Locale.ENGLISH).startsWith("apache")) {
            name = "Apache " + name;
        }
        DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "name", name);
    }

    /**
     * Write DOAP description.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#description">http://usefulinc.com/ns/doap#description</a>
     * @see <a href="http://usefulinc.com/ns/doap#shortdesc">http://usefulinc.com/ns/doap#shortdesc</a>
     */
    private void writeDescription(XMLWriter writer, Project project) {
        boolean addComment = false;
        String description = DoapUtil.interpolate(doapOptions.getDescription(), project, session.getSettings());
        if (description == null || description.isEmpty()) {
            messages.addMessage(
                    new String[] {"doapOptions", "description"}, null, UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
        } else {
            DoapUtil.writeComment(writer, "Plain text description of a project, of 2-4 sentences in length.");
            addComment = true;
            DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "description", description, lang);
        }

        String comment = "Short plain text description of a project.";
        String shortdesc = DoapUtil.interpolate(doapOptions.getShortdesc(), project, session.getSettings());
        if (shortdesc == null || shortdesc.isEmpty()) {
            messages.addMessage(
                    new String[] {"doapOptions", "shortdesc"}, null, UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
            return;
        }
        if (description.equals(shortdesc)) {
            // try to get the first 10 words of the description
            String sentence = StringUtils.split(shortdesc, ".")[0];
            if (StringUtils.split(sentence, " ").length > 10) {
                messages.addMessage(new String[] {"doapOptions", "shortdesc"}, null, UserMessages.SHORT_DESC_TOO_LONG);
                return;
            }
            if (!addComment) {
                DoapUtil.writeComment(writer, comment);
            }
            DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "shortdesc", sentence, lang);
            return;
        }
        if (!addComment) {
            DoapUtil.writeComment(writer, comment);
        }
        DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "shortdesc", shortdesc, lang);
    }

    /**
     * Write DOAP created.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#created">http://usefulinc.com/ns/doap#created</a>
     */
    private void writeCreated(XMLWriter writer, Project project) {
        String created = DoapUtil.interpolate(doapOptions.getCreated(), project, session.getSettings());
        if (created == null || created.isEmpty()) {
            messages.addMessage(
                    new String[] {"doapOptions", "created"}, null, UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
            return;
        }

        try {
            DOAP_DATE_FORMAT.parse(created);
        } catch (ParseException e) {
            messages.addMessage(new String[] {"doapOptions", "created"}, null, UserMessages.INVALID_DATE);
            return;
        }

        DoapUtil.writeComment(writer, "Date when something was created, in YYYY-MM-DD form. e.g. 2004-04-05");
        DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "created", created);
    }

    /**
     * Write DOAP homepage and old-homepage.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#homepage">http://usefulinc.com/ns/doap#homepage</a>
     * @see <a href="http://usefulinc.com/ns/doap#old-homepage">http://usefulinc.com/ns/doap#old-homepage</a>
     */
    private void writeHomepage(XMLWriter writer, Project project) {
        String homepage = DoapUtil.interpolate(doapOptions.getHomepage(), project, session.getSettings());
        if (homepage == null || homepage.isEmpty()) {
            messages.addMessage(
                    new String[] {"doapOptions", "homepage"}, null, UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
        } else {
            try {
                new URL(homepage);

                DoapUtil.writeComment(writer, "URL of a project's homepage, associated with exactly one project.");
                DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "homepage", homepage);
            } catch (MalformedURLException e) {
                messages.addMessage(new String[] {"doapOptions", "homepage"}, homepage, UserMessages.INVALID_URL);
            }
        }

        if (StringUtils.isNotEmpty(doapOptions.getOldHomepage())) {
            String oldHomepage = DoapUtil.interpolate(doapOptions.getOldHomepage(), project, session.getSettings());
            if (oldHomepage == null || oldHomepage.isEmpty()) {
                return;
            }

            try {
                new URL(oldHomepage);

                DoapUtil.writeComment(writer, "URL of a project's past homepage, associated with exactly one project.");
                DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "old-homepage", oldHomepage);
            } catch (MalformedURLException e) {
                messages.addMessage(new String[] {"doapOptions", "oldHomepage"}, oldHomepage, UserMessages.INVALID_URL);
            }
        }
    }

    /**
     * Write DOAP programming-language.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#programming-language">
     *      http://usefulinc.com/ns/doap#programming-language</a>
     */
    private void writeProgrammingLanguage(XMLWriter writer, Project project) {
        if (StringUtils.isEmpty(doapOptions.getProgrammingLanguage())) {
            messages.addMessage(
                    new String[] {"doapOptions", "programmingLanguage"},
                    null,
                    UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
            return;
        }

        boolean addComment = false;
        String comment = "Programming language.";
        if (StringUtils.isNotEmpty(doapOptions.getProgrammingLanguage())) {
            String[] languages = StringUtils.split(doapOptions.getProgrammingLanguage(), ",");
            for (String language : languages) {
                language = language.trim();

                if (asfExtOptions.isIncluded()) {
                    String asfLanguage = ASFExtOptionsUtil.getProgrammingLanguageSupportedByASF(language);
                    if (asfLanguage == null) {
                        messages.getErrorMessages()
                                .add("The "
                                        + messages.toConfiguration(
                                                new String[] {"doapOptions", "programmingLanguage"}, language)
                                        + " parameter is not supported by ASF. "
                                        + "Should be one of "
                                        + Arrays.toString(ASFExtOptionsUtil.PROGRAMMING_LANGUAGES));
                    } else {
                        if (!addComment) {
                            DoapUtil.writeComment(writer, comment);
                            addComment = true;
                        }
                        DoapUtil.writeElement(
                                writer, doapOptions.getXmlnsPrefix(), "programming-language", asfLanguage);
                    }
                } else {
                    if (!addComment) {
                        DoapUtil.writeComment(writer, comment);
                        addComment = true;
                    }
                    DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "programming-language", language);
                }
            }
        }
    }

    /**
     * Write DOAP category.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#category">http://usefulinc.com/ns/doap#category</a>
     */
    private void writeCategory(XMLWriter writer, Project project) {
        if (StringUtils.isEmpty(doapOptions.getCategory())) {
            messages.addMessage(
                    new String[] {"doapOptions", "category"}, null, UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
            return;
        }

        // TODO: how to lookup category, map it, or just declare it.
        boolean addComment = false;
        String comment = "A category of project.";
        if (StringUtils.isNotEmpty(doapOptions.getCategory())) {
            String[] categories = StringUtils.split(doapOptions.getCategory(), ",");
            for (String category : categories) {
                category = category.trim();

                if (asfExtOptions.isIncluded()) {
                    String asfCategory = ASFExtOptionsUtil.getCategorySupportedByASF(category);
                    if (asfCategory == null) {
                        messages.getErrorMessages()
                                .add("The "
                                        + messages.toConfiguration(new String[] {"doapOptions", "category"}, category)
                                        + " parameter is not supported by ASF. Should be one of "
                                        + Arrays.toString(ASFExtOptionsUtil.CATEGORIES));
                    } else {
                        if (!addComment) {
                            DoapUtil.writeComment(writer, comment);
                            addComment = true;
                        }
                        DoapUtil.writeRdfResourceElement(
                                writer,
                                doapOptions.getXmlnsPrefix(),
                                "category",
                                ASFExtOptionsUtil.CATEGORY_RESOURCE + asfCategory);
                    }
                } else {
                    if (!addComment) {
                        DoapUtil.writeComment(writer, comment);
                        addComment = true;
                    }
                    DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "category", category);
                }
            }
        }
    }

    /**
     * Write DOAP download-page and download-mirror.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#download-page">http://usefulinc.com/ns/doap#download-page</a>
     * @see <a href="http://usefulinc.com/ns/doap#download-mirror">http://usefulinc.com/ns/doap#download-mirror</a>
     */
    private void writeDownloadPage(XMLWriter writer, Project project) {
        String downloadPage = DoapUtil.interpolate(doapOptions.getDownloadPage(), project, session.getSettings());
        if (downloadPage == null || downloadPage.isEmpty()) {
            messages.addMessage(
                    new String[] {"doapOptions", "downloadPage"}, null, UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
            return;
        }

        try {
            new URL(downloadPage);

            DoapUtil.writeComment(writer, "Download page.");
            DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "download-page", downloadPage);
        } catch (MalformedURLException e) {
            messages.addMessage(new String[] {"doapOptions", "downloadPage"}, downloadPage, UserMessages.INVALID_URL);
        }

        if (StringUtils.isNotEmpty(doapOptions.getDownloadMirror())) {
            boolean addComment = false;
            String[] downloadMirrors = StringUtils.split(doapOptions.getDownloadMirror(), ",");
            for (String downloadMirror : downloadMirrors) {
                downloadMirror = downloadMirror.trim();

                try {
                    new URL(downloadMirror);

                    if (!addComment) {
                        DoapUtil.writeComment(writer, "Mirror of software download web page.");
                        addComment = true;
                    }
                    DoapUtil.writeRdfResourceElement(
                            writer, doapOptions.getXmlnsPrefix(), "download-mirror", downloadMirror);
                } catch (MalformedURLException e) {
                    messages.addMessage(
                            new String[] {"doapOptions", "downloadMirror"}, downloadMirror, UserMessages.INVALID_URL);
                }
            }
        }
    }

    /**
     * Write DOAP OS.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#os">http://usefulinc.com/ns/doap#os</a>
     */
    private void writeOS(XMLWriter writer, Project project) {
        String osList = DoapUtil.interpolate(doapOptions.getOs(), project, session.getSettings());
        if (osList == null || osList.isEmpty()) {
            return;
        }

        DoapUtil.writeComment(writer, "Operating system that a project is limited to.");
        String[] oses = StringUtils.split(osList, ",");
        for (String os : oses) {
            DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "os", os.trim());
        }
    }

    /**
     * Write DOAP screenshots.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#screenshots">http://usefulinc.com/ns/doap#screenshots</a>
     */
    private void writeScreenshots(XMLWriter writer, Project project) {
        String screenshots = DoapUtil.interpolate(doapOptions.getScreenshots(), project, session.getSettings());
        if (screenshots == null || screenshots.isEmpty()) {
            return;
        }

        screenshots = screenshots.trim();
        try {
            new URL(screenshots);
        } catch (MalformedURLException e) {
            messages.addMessage(new String[] {"doapOptions", "screenshots"}, screenshots, UserMessages.INVALID_URL);
            return;
        }

        DoapUtil.writeComment(writer, "Web page with screenshots of project.");
        DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "screenshots", screenshots);
    }

    /**
     * Write DOAP wiki.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#wiki">http://usefulinc.com/ns/doap#wiki</a>
     */
    private void writeWiki(XMLWriter writer, Project project) {
        String wiki = DoapUtil.interpolate(doapOptions.getWiki(), project, session.getSettings());
        if (wiki == null || wiki.isEmpty()) {
            return;
        }

        wiki = wiki.trim();
        try {
            new URL(wiki);
        } catch (MalformedURLException e) {
            messages.addMessage(new String[] {"doapOptions", "wiki"}, wiki, UserMessages.INVALID_URL);
            return;
        }

        DoapUtil.writeComment(writer, "URL of Wiki for collaborative discussion of project.");
        DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "wiki", wiki);
    }

    /**
     * Write DOAP licenses.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#license">http://usefulinc.com/ns/doap#license</a>
     */
    private void writeLicenses(XMLWriter writer, Project project) {
        String license = DoapUtil.interpolate(doapOptions.getLicense(), project, session.getSettings());
        if (license == null || license.isEmpty()) {
            boolean added = false;
            @SuppressWarnings("unchecked")
            List<License> licenses = project.getModel().getLicenses();
            if (licenses.size() > 1) {
                for (int i = 1; i < licenses.size(); i++) {
                    if (StringUtils.isEmpty(licenses.get(i).getUrl())) {
                        continue;
                    }

                    String licenseUrl = licenses.get(i).getUrl().trim();
                    try {
                        new URL(licenseUrl);

                        DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "license", licenseUrl);
                        added = true;
                    } catch (MalformedURLException e) {
                        messages.addMessage(
                                new String[] {"project", "licenses", "license", "url"},
                                licenseUrl,
                                UserMessages.INVALID_URL);
                    }
                }
            }

            if (!added) {
                messages.addMessage(
                        new String[] {"doapOptions", "license"}, null, UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
            }
            return;
        }

        try {
            new URL(license);

            DoapUtil.writeComment(writer, "The URI of the license the software is distributed under.");
            DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "license", license);
        } catch (MalformedURLException e) {
            messages.addMessage(new String[] {"doapOptions", "license"}, license, UserMessages.INVALID_URL);
        }
    }

    /**
     * Write DOAP bug-database.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#bug-database">http://usefulinc.com/ns/doap#bug-database</a>
     */
    private void writeBugDatabase(XMLWriter writer, Project project) {
        String bugDatabase = DoapUtil.interpolate(doapOptions.getBugDatabase(), project, session.getSettings());
        if (bugDatabase == null || bugDatabase.isEmpty()) {
            messages.addMessage(
                    new String[] {"doapOptions", "bugDatabase"}, null, UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
            return;
        }

        try {
            new URL(bugDatabase);

            DoapUtil.writeComment(writer, "Bug database.");
            DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "bug-database", bugDatabase);
        } catch (MalformedURLException e) {
            messages.addMessage(new String[] {"doapOptions", "bugDatabase"}, bugDatabase, UserMessages.INVALID_URL);
        }
    }

    /**
     * Write DOAP mailing-list.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#mailing-list">http://usefulinc.com/ns/doap#mailing-list</a>
     * @see DoapOptions#getMailingList()
     */
    private void writeMailingList(XMLWriter writer, Project project) {
        String ml = DoapUtil.interpolate(doapOptions.getMailingList(), project, session.getSettings());
        if (ml == null || ml.isEmpty()) {
            messages.addMessage(
                    new String[] {"doapOptions", "mailingList"}, null, UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
            return;
        }

        try {
            new URL(ml);

            DoapUtil.writeComment(writer, "Mailing lists.");
            DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "mailing-list", ml);
        } catch (MalformedURLException e) {
            messages.addMessage(new String[] {"doapOptions", "mailingList"}, ml, UserMessages.INVALID_URL);
        }
    }

    /**
     * Write all DOAP releases.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @throws MojoException if any
     * @see <a href="http://usefulinc.com/ns/doap#release">http://usefulinc.com/ns/doap#release</a>
     * @see <a href="http://usefulinc.com/ns/doap#Version">http://usefulinc.com/ns/doap#Version</a>
     */
    private void writeReleases(XMLWriter writer, Project project) throws MojoException {
        Metadata metadata = null;

        List<RemoteRepository> remoteRepositories = getProjectManager().getRemoteProjectRepositories(this.project);

        for (RemoteRepository repo : remoteRepositories) {
            if (isPolicyEnabled(repo, true)) {
                continue;
            }
            if (isPolicyEnabled(repo, false)) {
                metadata = resolveVersioningMetadata(project, repo);
                break;
            }
        }

        if (metadata == null || metadata.getVersioning() == null) {
            messages.getWarnMessages()
                    .add("No versioning was found for " + project.getGroupId() + ":" + project.getArtifactId()
                            + ". Ignored DOAP <release/> tag.");
            return;
        }

        // the model of the metadata is immutable
        List<String> versions = new ArrayList<>(metadata.getVersioning().getVersions());

        // Recent releases in first
        Collections.reverse(versions);
        boolean addComment = false;
        int i = 0;
        for (String version : versions) {
            if (!addComment) {
                DoapUtil.writeComment(writer, "Project releases.");
                addComment = true;
            }

            DoapUtil.writeStartElement(writer, doapOptions.getXmlnsPrefix(), "release");
            DoapUtil.writeStartElement(writer, doapOptions.getXmlnsPrefix(), "Version");

            DoapUtil.writeStartElement(writer, doapOptions.getXmlnsPrefix(), "name");
            writer.writeText(project.getModel().getName() + " - " + version);
            writer.endElement(); // name

            DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "revision", version);

            // list all file release from all remote repos
            for (RemoteRepository repo : remoteRepositories) {
                String type = StringUtils.isNotEmpty(fileReleaseType)
                        ? fileReleaseType
                        : project.getModel().getPackaging();
                String classifier = StringUtils.isNotEmpty(fileReleaseClassifier) ? fileReleaseClassifier : null;
                String location = getDefaultLayoutLocation(project, version, classifier, type);
                String fileRelease = repo.getUrl() + "/" + location;

                if (!isArtifactInRepository(location, repo)) {
                    getLog().debug(location + " is not in the repository " + repo.getId());
                    continue;
                }

                DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "file-release", fileRelease);

                Date releaseDate = null;

                // If the last updated date is not available, skip it
                if (metadata.getVersioning().getLastUpdated() == null) {
                    continue;
                }

                try {
                    releaseDate = REPOSITORY_DATE_FORMAT.parse(
                            metadata.getVersioning().getLastUpdated());
                } catch (ParseException e) {
                    getLog().error("Unable to parse date '"
                            + metadata.getVersioning().getLastUpdated() + "'");
                    continue;
                }

                // See MDOAP-11
                if (i == 0) {
                    DoapUtil.writeElement(
                            writer, doapOptions.getXmlnsPrefix(), "created", DOAP_DATE_FORMAT.format(releaseDate));
                }
            }

            writer.endElement(); // Version
            writer.endElement(); // release

            i++;
        }
    }

    /**
     * Whether the given repository serves releases or snapshots. The Maven 4 API exposes no repository policy on
     * {@link RemoteRepository}, so the policies are read from the repository of the same id in the effective model
     * of the project. A repository that is not declared there, such as a mirror or a repository from
     * {@code settings.xml}, is taken as serving releases only.
     *
     * @param repository not null
     * @param snapshots  true for the snapshot policy, false for the release policy
     * @return true if the policy is enabled
     */
    private boolean isPolicyEnabled(RemoteRepository repository, boolean snapshots) {
        for (org.apache.maven.api.model.Repository declared : project.getModel().getRepositories()) {
            if (repository.getId().equals(declared.getId())) {
                org.apache.maven.api.model.RepositoryPolicy policy =
                        snapshots ? declared.getSnapshots() : declared.getReleases();
                return policy == null || policy.isEnabled();
            }
        }
        return !snapshots;
    }

    /**
     * The location of an artifact in a repository using the default layout, relative to the repository URL. The
     * Maven 4 API has no repository layout service, and Maven has no other layout.
     */
    private String getDefaultLayoutLocation(Project project, String version, String classifier, String type) {
        String extension;
        try {
            extension = session.requireType(type).getExtension();
        } catch (RuntimeException e) {
            // unknown type, which Maven 3 handled with a default artifact handler whose extension is the type
            extension = type;
        }

        StringBuilder location = new StringBuilder()
                .append(project.getGroupId().replace('.', '/'))
                .append('/')
                .append(project.getArtifactId())
                .append('/')
                .append(version)
                .append('/')
                .append(project.getArtifactId())
                .append('-')
                .append(version);
        if (classifier != null) {
            location.append('-').append(classifier);
        }
        return location.append('.').append(extension).toString();
    }

    /**
     * Fetch the release versioning metadata of the given project from the given repository.
     *
     * @param project    the Maven project, not null
     * @param repository the repository to read the metadata from, not null
     * @return the parsed maven-metadata.xml, or null if the repository does not carry one
     * @throws MojoException if the metadata could not be retrieved or parsed
     */
    private Metadata resolveVersioningMetadata(Project project, RemoteRepository repository) throws MojoException {
        String location =
                project.getGroupId().replace('.', '/') + "/" + project.getArtifactId() + "/maven-metadata.xml";

        Optional<String> content;
        try (Transport transport = getTransportProvider().transport(session, repository)) {
            content = transport.getString(URI.create(location));
        } catch (IOException | RuntimeException e) {
            throw new MojoException(
                    project.getGroupId() + ":" + project.getArtifactId()
                            + " metadata could not be retrieved from repository " + repository.getId()
                            + " due to an error: " + e.getMessage(),
                    e);
        }

        // a repository that simply carries no metadata for this project is not an error
        if (!content.isPresent()) {
            return null;
        }

        try (Reader reader = new StringReader(content.get())) {
            return new MetadataStaxReader().read(reader, false);
        } catch (IOException | XMLStreamException e) {
            throw new MojoException(
                    project.getGroupId() + ":" + project.getArtifactId() + " metadata could not be parsed: "
                            + e.getMessage(),
                    e);
        }
    }

    /**
     * Check if an artifact exists in a remote repo. The Maven 4 transport has no existence check, so this reads the
     * SHA-1 checksum next to the artifact, which repositories publish for every artifact, instead of the artifact.
     */
    private boolean isArtifactInRepository(String location, RemoteRepository repository) {
        try (Transport transport = getTransportProvider().transport(session, repository)) {
            return transport.getBytes(URI.create(location + ".sha1")).isPresent();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Write all DOAP repositories.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#Repository">http://usefulinc.com/ns/doap#Repository</a>
     * @see <a href="http://usefulinc.com/ns/doap#SVNRepository">http://usefulinc.com/ns/doap#SVNRepository</a>
     */
    private void writeSourceRepositories(XMLWriter writer, Project project) {
        String anonymousConnection =
                DoapUtil.interpolate(doapOptions.getScmAnonymous(), project, session.getSettings());
        if (anonymousConnection == null || anonymousConnection.isEmpty()) {
            messages.addMessage(
                    new String[] {"doapOptions", "scmAnonymousConnection"},
                    null,
                    UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
        } else {
            DoapUtil.writeComment(writer, "Anonymous Source Repository.");

            try {
                new URL(anonymousConnection);

                DoapUtil.writeStartElement(writer, doapOptions.getXmlnsPrefix(), "repository");
                DoapUtil.writeStartElement(writer, doapOptions.getXmlnsPrefix(), "Repository");
                DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "location", anonymousConnection);
                writer.endElement(); // Repository
                writer.endElement(); // repository
            } catch (MalformedURLException e) {
                writeSourceRepository(writer, project, anonymousConnection);
            }
        }

        String devConnection = DoapUtil.interpolate(doapOptions.getScmDeveloper(), project, session.getSettings());
        if (devConnection == null || devConnection.isEmpty()) {
            messages.addMessage(
                    new String[] {"doapOptions", "scmDeveloperConnection"},
                    null,
                    UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
        } else {
            DoapUtil.writeComment(writer, "Developer Source Repository.");

            try {
                new URL(devConnection);

                DoapUtil.writeStartElement(writer, doapOptions.getXmlnsPrefix(), "repository");
                DoapUtil.writeStartElement(writer, doapOptions.getXmlnsPrefix(), "Repository");
                DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "location", devConnection);
                writer.endElement(); // Repository
                writer.endElement(); // repository
            } catch (MalformedURLException e) {
                writeSourceRepository(writer, project, devConnection);
            }
        }
    }

    /**
     * Write a DOAP repository, for instance:
     * <p/>
     * <pre>
     *   &lt;repository&gt;
     *     &lt;SVNRepository&gt;
     *       &lt;location rdf:resource="http://svn.apache.org/repos/asf/maven/components/trunk/"/&gt;
     *       &lt;browse rdf:resource="http://svn.apache.org/viewvc.cgi/maven/components/trunk/"/&gt;
     *     &lt;/SVNRepository&gt;
     *   &lt;/repository&gt;
     * </pre>
     *
     * @param writer     not null
     * @param project    the Maven project, not null
     * @param connection not null
     * @see <a href="http://usefulinc.com/ns/doap#Repository">http://usefulinc.com/ns/doap#Repository</a>
     * @see <a href="http://usefulinc.com/ns/doap#SVNRepository">http://usefulinc.com/ns/doap#SVNRepository</a>
     * @see <a href="http://usefulinc.com/ns/doap#GitRepository">http://usefulinc.com/ns/doap#GitRepository</a>
     */
    private void writeSourceRepository(XMLWriter writer, Project project, String connection) {
        String[] scm = DoapUtil.splitScmConnection(connection);
        String repositoryType = scm == null ? null : DoapUtil.getScmRepositoryType(scm[0]);

        DoapUtil.writeStartElement(writer, doapOptions.getXmlnsPrefix(), "repository");

        if (repositoryType != null) {
            DoapUtil.writeStartElement(writer, doapOptions.getXmlnsPrefix(), repositoryType);

            DoapUtil.writeRdfResourceElement(
                    writer, doapOptions.getXmlnsPrefix(), "location", DoapUtil.removeUserInfo(scm[1]));
        } else {
            /*
             * CVS, Arch, BitKeeper and other systems: DOAP describes them with more than a location, so only a
             * generic repository is written.
             */
            DoapUtil.writeStartElement(writer, doapOptions.getXmlnsPrefix(), "Repository");

            if (connection.length() < 4) {
                throw new IllegalArgumentException("The source repository connection is too short.");
            }

            DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "location", connection.substring(4));
        }

        DoapUtil.writeRdfResourceElement(
                writer,
                doapOptions.getXmlnsPrefix(),
                "browse",
                project.getModel().getScm().getUrl());

        writer.endElement(); // typed repository || Repository
        writer.endElement(); // repository
    }

    /**
     * Write a Foaf Organization, for instance:
     * <p/>
     * <pre>
     *   &lt;<foaf:Organization&gt;
     *     &lt;foaf:name&gt;YoyoDyne&lt;/foaf:name&gt;
     *     &lt;foaf:homepage rdf:resource="http://yoyodyne.example.org"/&gt;
     *     &lt;foaf:member rdf:nodeID="benny_profane"&gt;
     *   &lt;/foaf:Organization&gt;
     * </pre>
     *
     * @param writer                   not null
     * @param developersOrContributors list of <code>{@link Developer}/{@link Contributor}</code>
     * @param doapType                 not null
     * @see ContributorWriter#writeContributor(XMLWriter, Contributor, String, String)
     */
    private void writeOrganizations(XMLWriter writer) {
        Set<Entry<String, DoapUtil.Organization>> organizations = DoapUtil.getOrganizations();

        for (Entry<String, DoapUtil.Organization> organizationEntry : organizations) {
            DoapUtil.Organization organization = organizationEntry.getValue();

            DoapUtil.writeStartElement(writer, "foaf", "Organization");
            if (!StringUtils.isEmpty(organization.getName())) {
                DoapUtil.writeElement(writer, "foaf", "name", organization.getName());
            }
            if (!StringUtils.isEmpty(organization.getUrl())) {
                try {
                    new URL(organization.getUrl());

                    DoapUtil.writeRdfResourceElement(writer, "foaf", "homepage", organization.getUrl());
                } catch (MalformedURLException e) {
                    messages.getErrorMessages()
                            .add("The organization URL " + organization.getUrl() + " is not a valid URL.");
                }
            }
            List<String> members = organization.getMembers();
            for (String member : members) {
                DoapUtil.writeRdfNodeIdElement(writer, "foaf", "member", member);
            }
            writer.endElement(); // foaf:Organization
        }
    }

    /**
     * Write DOAP audience.
     *
     * @param writer not null
     * @see <a href="http://usefulinc.com/ns/doap#audience">http://usefulinc.com/ns/doap#audience</a>
     * @since 1.1
     */
    private void writeAudience(XMLWriter writer) {
        String audience = DoapUtil.interpolate(doapOptions.getAudience(), project, session.getSettings());
        if (audience == null || audience.isEmpty()) {
            return;
        }

        DoapUtil.writeComment(writer, "Audience.");
        DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "audience", audience);
    }

    /**
     * Write DOAP blog.
     *
     * @param writer not null
     * @see <a href="http://usefulinc.com/ns/doap#blog">http://usefulinc.com/ns/doap#blog</a>
     * @since 1.1
     */
    private void writeBlog(XMLWriter writer) {
        String blog = DoapUtil.interpolate(doapOptions.getBlog(), project, session.getSettings());
        if (StringUtils.isEmpty(doapOptions.getBlog())) {
            return;
        }

        blog = blog.trim();
        try {
            new URL(blog);
        } catch (MalformedURLException e) {
            messages.addMessage(new String[] {"doapOptions", "blog"}, blog, UserMessages.INVALID_URL);
            return;
        }

        DoapUtil.writeComment(writer, "Blog page.");
        DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "blog", blog);
    }

    /**
     * Write DOAP plateform.
     *
     * @param writer not null
     * @see <a href="http://usefulinc.com/ns/doap#plateform">http://usefulinc.com/ns/doap#plateform</a>
     * @since 1.1
     */
    private void writePlateform(XMLWriter writer) {
        if (StringUtils.isEmpty(doapOptions.getPlatform())) {
            return;
        }

        DoapUtil.writeComment(writer, "Platform.");
        String[] platforms = StringUtils.split(doapOptions.getPlatform(), ",");
        for (String platform : platforms) {
            DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "platform", platform.trim());
        }
    }

    /**
     * Write DOAP vendor.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://usefulinc.com/ns/doap#vendor">http://usefulinc.com/ns/doap#vendor</a>
     * @since 1.1
     */
    private void writeVendor(XMLWriter writer, Project project) {
        String vendor = DoapUtil.interpolate(doapOptions.getVendor(), project, session.getSettings());
        if (vendor == null || vendor.isEmpty()) {
            return;
        }

        DoapUtil.writeComment(writer, "Vendor.");
        DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "vendor", vendor);
    }

    /**
     * Write DOAP language.
     *
     * @param writer not null
     * @see <a href="http://usefulinc.com/ns/doap#language">http://usefulinc.com/ns/doap#language</a>
     * @since 1.1
     */
    private void writeLanguage(XMLWriter writer) {
        if (StringUtils.isEmpty(doapOptions.getLanguage())) {
            return;
        }

        boolean addComment = false;
        String[] languages = StringUtils.split(doapOptions.getLanguage(), ",");
        for (String language : languages) {
            language = language.trim();

            if (Arrays.binarySearch(Locale.getISOLanguages(), language) < 0) {
                messages.addMessage(new String[] {"doapOptions", "languages"}, language, UserMessages.INVALID_ISO_DATE);
                continue;
            }

            if (!addComment) {
                DoapUtil.writeComment(writer, "Language.");
                addComment = true;
            }
            DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "language", language);
        }
    }

    /**
     * Write DOAP service-endpoint.
     *
     * @param writer not null
     * @see <a href="http://usefulinc.com/ns/doap#service-endpoint">http://usefulinc.com/ns/doap#service-endpoint</a>
     * @since 1.1
     */
    private void writeServiceEndpoint(XMLWriter writer) {
        String serviceEndpoint = DoapUtil.interpolate(doapOptions.getServiceEndpoint(), project, session.getSettings());
        if (serviceEndpoint == null || serviceEndpoint.isEmpty()) {
            return;
        }

        serviceEndpoint = serviceEndpoint.trim();
        try {
            new URL(serviceEndpoint);
        } catch (MalformedURLException e) {
            messages.addMessage(
                    new String[] {"doapOptions", "serviceEndpoint"}, serviceEndpoint, UserMessages.INVALID_URL);
            return;
        }

        DoapUtil.writeComment(writer, "Service endpoint.");
        DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "service-endpoint", serviceEndpoint);
    }

    /**
     * Write DOAP implements.
     *
     * @param writer not null
     * @see <a href="http://usefulinc.com/ns/doap#implements">http://usefulinc.com/ns/doap#implements</a>
     * @since 1.1
     */
    private void writeImplements(XMLWriter writer) {
        if (StringUtils.isEmpty(doapOptions.getImplementations())) {
            return;
        }

        DoapUtil.writeComment(writer, "Implements.");
        String[] implementations = StringUtils.split(doapOptions.getImplementations(), ",");
        for (String implementation : implementations) {
            DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "implements", implementation.trim());
        }
    }

    /**
     * Write the extra DOAP extensions.
     *
     * @param writer not null
     * @since 1.1
     */
    private void writeExtensions(XMLWriter writer) {
        if (!(extOptions != null
                && extOptions.length > 0
                && !extOptions[0].getExtensions().isEmpty())) {
            return;
        }

        for (ExtOptions extOption : extOptions) {
            Map<Object, String> map = extOption.getExtensions();
            DoapUtil.writeExtra(
                    writer,
                    project,
                    session.getSettings(),
                    "Other extension vocabulary.",
                    map,
                    extOption.getXmlnsPrefix());
        }
    }

    // ----------------------------------------------------------------------
    // Static methods
    // ----------------------------------------------------------------------

}
