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
import java.util.Comparator;
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
import org.apache.maven.plugin.doap.options.Standard;
import org.apache.maven.scm.manager.BasicScmManager;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.svn.repository.SvnScmProviderRepository;
import org.apache.maven.scm.provider.svn.svnexe.SvnExeScmProvider;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
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
     * Maven SCM Manager, only used to recognise Subversion URLs. The SCM providers other than Subversion are not on
     * the classpath, so any other URL is written as a generic DOAP repository.
     *
     * @since 1.0
     */
    private ScmManager scmManager = createScmManager();

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
     * The category which should be displayed in the DOAP file.
     *
     * @deprecated Since 1.0. Instead of, configure
     *             <code>&lt;doapOptions&gt;&lt;category/&gt;&lt;/doapOptions&gt;</code> parameter.
     */
    @Parameter(property = "category")
    private String category;

    /**
     * The programming language which should be displayed in the DOAP file.
     *
     * @deprecated Since 1.0. Instead of, configure
     *             <code>&lt;doapOptions&gt;&lt;programmingLanguage/&gt;&lt;/doapOptions&gt;</code> parameter.
     */
    @Parameter(property = "language")
    private String language;

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
     * @see <a href="http://svn.apache.org/repos/asf/infrastructure/site-tools/trunk/projects/asfext">
     *      http://svn.apache.org/repos/asf/infrastructure/site-tools/trunk/projects/asfext</a>
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
    private UserMessages messages = new UserMessages();

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
        writeContributors(writer, new ArrayList<Contributor>(developers));

        // Contributors
        List<Contributor> contributors = new ArrayList<>(project.getModel().getContributors());
        writeContributors(writer, contributors);

        // Extra DOAP
        Map<Object, String> map = doapOptions.getExtra();
        writeExtra(writer, project, "Extra DOAP vocabulary.", map, doapOptions.getXmlnsPrefix());

        // ASFext
        writeASFext(writer, project);

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
        if (StringUtils.isEmpty(doapOptions.getProgrammingLanguage()) && (language == null || language.isEmpty())) {
            messages.addMessage(
                    new String[] {"doapOptions", "programmingLanguage"},
                    null,
                    UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
            return;
        }

        boolean addComment = false;
        String comment = "Programming language.";
        if (language != null && !language.isEmpty()) // backward compatible
        {
            getLog().warn("The <language/> parameter is deprecated, please use "
                    + messages.toConfiguration(new String[] {"doapOptions", "programmingLanguage"}, null)
                    + " parameter instead of.");

            language = language.trim();

            if (asfExtOptions.isIncluded()) {
                String asfLanguage = ASFExtOptionsUtil.getProgrammingLanguageSupportedByASF(language);
                if (asfLanguage == null) {
                    messages.getErrorMessages()
                            .add("The deprecated " + messages.toConfiguration(new String[] {"language"}, language)
                                    + " parameter is not supported by ASF. Should be one of "
                                    + Arrays.toString(ASFExtOptionsUtil.PROGRAMMING_LANGUAGES));
                } else {
                    DoapUtil.writeComment(writer, comment);
                    addComment = true;
                    DoapUtil.writeElement(
                            writer, doapOptions.getXmlnsPrefix(), "programming-language", asfLanguage.trim());
                }
            } else {
                DoapUtil.writeComment(writer, comment);
                addComment = true;
                DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "programming-language", language.trim());
            }
        }

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
        if (StringUtils.isEmpty(doapOptions.getCategory()) && (category == null || category.isEmpty())) {
            messages.addMessage(
                    new String[] {"doapOptions", "category"}, null, UserMessages.REQUIRED_BY_ASF_OR_RECOMMENDED);
            return;
        }

        // TODO: how to lookup category, map it, or just declare it.
        boolean addComment = false;
        String comment = "A category of project.";
        if (category != null && !category.isEmpty()) // backward compatible
        {
            getLog().warn("The <category/> parameter is deprecated, please use "
                    + messages.toConfiguration(new String[] {"doapOptions", "category"}, null)
                    + " parameter instead of.");

            category = category.trim();

            if (asfExtOptions.isIncluded()) {
                String asfCategory = ASFExtOptionsUtil.getCategorySupportedByASF(category);
                if (asfCategory == null) {
                    messages.getErrorMessages()
                            .add("The deprecated " + messages.toConfiguration(new String[] {"category"}, category)
                                    + " parameter is not supported by ASF. Should be one of "
                                    + Arrays.toString(ASFExtOptionsUtil.CATEGORIES));
                } else {
                    DoapUtil.writeComment(writer, comment);
                    addComment = true;
                    DoapUtil.writeRdfResourceElement(
                            writer,
                            doapOptions.getXmlnsPrefix(),
                            "category",
                            ASFExtOptionsUtil.CATEGORY_RESOURCE + asfCategory);
                }
            } else {
                DoapUtil.writeComment(writer, comment);
                addComment = true;
                DoapUtil.writeElement(writer, doapOptions.getXmlnsPrefix(), "category", category);
            }
        }

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
     */
    private void writeSourceRepository(XMLWriter writer, Project project, String connection) {
        ScmRepository repository = getScmRepository(connection);

        DoapUtil.writeStartElement(writer, doapOptions.getXmlnsPrefix(), "repository");

        if (isScmSystem(repository, "svn")) {
            DoapUtil.writeStartElement(writer, doapOptions.getXmlnsPrefix(), "SVNRepository");

            SvnScmProviderRepository svnRepo = (SvnScmProviderRepository) repository.getProviderRepository();

            DoapUtil.writeRdfResourceElement(writer, doapOptions.getXmlnsPrefix(), "location", svnRepo.getUrl());
        } else {
            /*
             * Supported DOAP repositories actually unsupported by SCM: BitKeeper
             * (http://usefulinc.com/ns/doap#BKRepository) Arch (http://usefulinc.com/ns/doap#ArchRepository) Other SCM
             * repos are unsupported by DOAP.
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

        writer.endElement(); // SVNRepository || Repository
        writer.endElement(); // repository
    }

    /**
     * Write all DOAP persons.
     *
     * @param writer       not null
     * @param contributors list of developers or contributors
     */
    private void writeContributors(XMLWriter writer, List<Contributor> contributors) {
        if (contributors == null || contributors.isEmpty()) {
            return;
        }

        boolean isDeveloper =
                Developer.class.isAssignableFrom(contributors.get(0).getClass());
        if (isDeveloper) {
            DoapUtil.writeComment(writer, "Main committers.");
        } else {
            DoapUtil.writeComment(writer, "Contributed persons.");
        }

        List<Contributor> maintainers = DoapUtil.getContributorsWithMaintainerRole(contributors);
        List<Contributor> developers = DoapUtil.getContributorsWithDeveloperRole(contributors);
        List<Contributor> documenters = DoapUtil.getContributorsWithDocumenterRole(contributors);
        List<Contributor> translators = DoapUtil.getContributorsWithTranslatorRole(contributors);
        List<Contributor> testers = DoapUtil.getContributorsWithTesterRole(contributors);
        List<Contributor> helpers = DoapUtil.getContributorsWithHelperRole(contributors);
        List<Contributor> unknowns = DoapUtil.getContributorsWithUnknownRole(contributors);

        // By default, all developers are maintainers and contributors are helpers
        if (isDeveloper) {
            maintainers.addAll(unknowns);
        } else {
            helpers.addAll(unknowns);
        }

        // all alphabetical
        if (developers.size() != 0) {
            writeContributor(writer, developers, "developer");
        }
        if (documenters.size() != 0) {
            writeContributor(writer, documenters, "documenter");
        }
        if (helpers.size() != 0) {
            writeContributor(writer, helpers, "helper");
        }
        if (maintainers.size() != 0) {
            writeContributor(writer, maintainers, "maintainer");
        }
        if (testers.size() != 0) {
            writeContributor(writer, testers, "tester");
        }
        if (translators.size() != 0) {
            writeContributor(writer, translators, "translator");
        }
    }

    /**
     * Write a DOAP maintainer or developer or documenter or translator or tester or helper, for instance:
     * <p/>
     * <pre>
     *   &lt;maintainer&gt;
     *     &lt;foaf:Person&gt;
     *       &lt;foaf:name&gt;Emmanuel Venisse&lt;/foaf:name&gt;
     *       &lt;foaf:mbox rdf:resource="mailto:evenisse@apache.org"/&gt;
     *     &lt;/foaf:Person&gt;
     *   &lt;/maintainer&gt;
     * </pre>
     *
     * @param writer                   not null
     * @param developersOrContributors list of <code>{@link Developer}/{@link Contributor}</code>
     * @param doapType                 not null
     * @see #writeContributor(XMLWriter, Object, String)
     */
    private void writeContributor(XMLWriter writer, List<Contributor> developersOrContributors, String doapType) {
        if (developersOrContributors == null || developersOrContributors.isEmpty()) {
            return;
        }

        sortContributors(developersOrContributors);

        for (Contributor developersOrContributor : developersOrContributors) {
            writeContributor(writer, developersOrContributor, doapOptions.getXmlnsPrefix(), doapType);
        }
    }

    /**
     * Writer a single developer or contributor.
     *
     * @param writer                 not null
     * @param xmlsPrefix             could be null
     * @param developerOrContributor not null, instance of <code>{@link Developer}/{@link Contributor}</code>
     * @param doapType               not null
     * @see <a href="http://usefulinc.com/ns/doap#maintainer">http://usefulinc.com/ns/doap#maintainer</a>
     * @see <a href="http://usefulinc.com/ns/doap#developer">http://usefulinc.com/ns/doap#developer</a>
     * @see <a href="http://usefulinc.com/ns/doap#documenter">http://usefulinc.com/ns/doap#documenter</a>
     * @see <a href="http://usefulinc.com/ns/doap#translator">http://usefulinc.com/ns/doap#translator</a>
     * @see <a href="http://usefulinc.com/ns/doap#tester">http://usefulinc.com/ns/doap#tester</a>
     * @see <a href="http://usefulinc.com/ns/doap#helper">http://usefulinc.com/ns/doap#helper</a>
     * @see <a href="http://xmlns.com/foaf/0.1/Person">http://xmlns.com/foaf/0.1/Person</a>
     * @see <a href="http://xmlns.com/foaf/0.1/name">http://xmlns.com/foaf/0.1/name</a>
     * @see <a href="http://xmlns.com/foaf/0.1/mbox">http://xmlns.com/foaf/0.1/mbox</a>
     * @see <a href="http://xmlns.com/foaf/0.1/Organization">http://xmlns.com/foaf/0.1/Organization</a>
     * @see <a href="http://xmlns.com/foaf/0.1/homepage">http://xmlns.com/foaf/0.1/homepage</a>
     */
    private void writeContributor(
            XMLWriter writer, Contributor developerOrContributor, String xmlsPrefix, String doapType) {
        if (developerOrContributor == null) {
            return;
        }

        if (doapType == null || doapType.isEmpty()) {
            throw new IllegalArgumentException("doapType is required.");
        }

        String name = developerOrContributor.getName();
        String email = developerOrContributor.getEmail();
        String organization = developerOrContributor.getOrganization();
        String organizationUrl = developerOrContributor.getOrganizationUrl();
        String homepage = developerOrContributor.getUrl();
        String nodeId = null;

        // Name is required to write doap
        if (name == null || name.isEmpty()) {
            messages.addMessage(
                    new String[] {"project", "developers|contributors", "developer|contributor", "name"},
                    null,
                    UserMessages.REQUIRED);
            return;
        }

        if (!(organization == null || organization.isEmpty())
                || !(organizationUrl == null || organizationUrl.isEmpty())) {
            DoapUtil.Organization doapOrganization = DoapUtil.addOrganization(organization, organizationUrl);
            nodeId = DoapUtil.getNodeId();
            doapOrganization.addMember(nodeId);
        }

        DoapUtil.writeStartElement(writer, xmlsPrefix, doapType);
        DoapUtil.writeStartElement(writer, "foaf", "Person");
        if (nodeId != null && !nodeId.isEmpty()) {
            writer.addAttribute("rdf:nodeID", nodeId);
        }
        DoapUtil.writeStartElement(writer, "foaf", "name");
        writer.writeText(name);
        writer.endElement(); // foaf:name
        if (email != null && !email.isEmpty()) {
            if (DoapUtil.isValidEmail(email)) {
                DoapUtil.writeRdfResourceElement(writer, "foaf", "mbox", "mailto:" + email);
            } else {
                messages.addMessage(
                        new String[] {"project", "developers|contributors", "developer|contributor", "email"},
                        null,
                        UserMessages.INVALID_EMAIL);
            }
        }
        if ((organization != null && !organization.isEmpty())
                && (organizationUrl != null && !organizationUrl.isEmpty())) {
            try {
                new URL(organizationUrl);

                DoapUtil.addOrganization(organization, organizationUrl);
            } catch (MalformedURLException e) {
                messages.addMessage(
                        new String[] {"project", "developers|contributors", "developer|contributor", "organizationUrl"},
                        organizationUrl,
                        UserMessages.INVALID_URL);
            }
        }
        if (homepage != null && !homepage.isEmpty()) {
            try {
                new URL(homepage);

                DoapUtil.writeRdfResourceElement(writer, "foaf", "homepage", homepage);
            } catch (MalformedURLException e) {
                messages.addMessage(
                        new String[] {"project", "developers|contributors", "developer|contributor", "homepage"},
                        homepage,
                        UserMessages.INVALID_URL);
            }
        }
        writer.endElement(); // foaf:Person
        writer.endElement(); // doapType
    }

    private static ScmManager createScmManager() {
        return new BasicScmManager() {
            {
                setScmProvider("svn", new SvnExeScmProvider());
            }
        };
    }

    /**
     * Return a <code>SCM repository</code> defined by a given url.
     *
     * @param scmUrl an SCM URL
     * @return a valid SCM repository or null
     */
    private ScmRepository getScmRepository(String scmUrl) {
        ScmRepository repo = null;
        if (!(scmUrl == null || scmUrl.isEmpty())) {
            try {
                repo = scmManager.makeScmRepository(scmUrl);
            } catch (NoSuchScmProviderException | ScmRepositoryException e) {
                if (getLog().isDebugEnabled()) {
                    getLog().debug(e.getMessage(), e);
                }
            }
        }

        return repo;
    }

    /**
     * Write the ASF extensions.
     *
     * @param writer  not null
     * @param project the Maven project, not null
     * @see <a href="http://svn.apache.org/repos/asf/infrastructure/site-tools/trunk/projects/asfext">
     *      http://svn.apache.org/repos/asf/infrastructure/site-tools/trunk/projects/asfext</a>
     * @see <a href="http://projects.apache.org/docs/pmc.html">http://projects.apache.org/docs/pmc.html</a>
     */
    private void writeASFext(XMLWriter writer, Project project) {
        if (!asfExtOptions.isIncluded()) {
            return;
        }

        DoapUtil.writeComment(writer, "ASF extension.");

        // asfext:pmc
        String pmc = DoapUtil.interpolate(asfExtOptions.getPmc(), project, session.getSettings());
        if (pmc != null && !pmc.isEmpty()) {
            DoapUtil.writeRdfResourceElement(writer, asfExtOptions.getXmlnsPrefix(), "pmc", pmc);
        } else {
            messages.addMessage(new String[] {"asfExtOptions", "pmc"}, null, UserMessages.REQUIRED_BY_ASF);
        }

        // asfext:name
        String name = DoapUtil.interpolate(asfExtOptions.getName(), project, session.getSettings());
        if (name != null && !name.isEmpty()) {
            if (!name.toLowerCase(Locale.ENGLISH).trim().startsWith("apache")) {
                name = "Apache " + name;
            }
            DoapUtil.writeElement(writer, asfExtOptions.getXmlnsPrefix(), "name", name);
        } else {
            messages.addMessage(new String[] {"asfExtOptions", "name"}, null, UserMessages.REQUIRED_BY_ASF);
        }

        String homepage = DoapUtil.interpolate(doapOptions.getHomepage(), project, session.getSettings());
        if (homepage != null && !homepage.isEmpty()) {
            try {
                new URL(homepage);

                DoapUtil.writeRdfResourceElement(writer, "foaf", "homepage", homepage);
            } catch (MalformedURLException e) {
                messages.addMessage(new String[] {"doapOptions", "homepage"}, homepage, UserMessages.INVALID_URL);
            }
        }

        // asfext:charter
        if (StringUtils.isEmpty(asfExtOptions.getCharter())) {
            messages.addMessage(new String[] {"asfExtOptions", "charter"}, null, UserMessages.REQUIRED_BY_ASF);
        } else {
            DoapUtil.writeElement(writer, asfExtOptions.getXmlnsPrefix(), "charter", asfExtOptions.getCharter());
        }

        // asfext:chair
        @SuppressWarnings("unchecked")
        List<Developer> developers = new ArrayList<Developer>(project.getModel().getDevelopers());
        sortContributors(developers);

        if (StringUtils.isNotEmpty(asfExtOptions.getChair())) {
            DoapUtil.writeStartElement(writer, asfExtOptions.getXmlnsPrefix(), "chair");
            DoapUtil.writeStartElement(writer, "foaf", "Person");
            DoapUtil.writeStartElement(writer, "foaf", "name");
            writer.writeText(asfExtOptions.getChair());
            writer.endElement(); // foaf:name
            writer.endElement(); // foaf:Person
            writer.endElement(); // asfext:chair
        } else {
            Developer chair = ASFExtOptionsUtil.findChair(developers);
            if (chair != null) {
                writeContributor(writer, chair, asfExtOptions.getXmlnsPrefix(), "chair");
                developers.remove(chair);
            } else {
                messages.addMessage(new String[] {"asfExtOptions", "chair"}, null, UserMessages.REQUIRED_BY_ASF);
            }
        }

        // asfext:member
        if (developers != null && developers.size() > 0) {
            List<Developer> pmcMembers = ASFExtOptionsUtil.findPMCMembers(developers);
            for (Developer pmcMember : pmcMembers) {
                writeContributor(writer, pmcMember, asfExtOptions.getXmlnsPrefix(), "member");
            }
        }

        writeASFImplements(writer);

        Map<Object, String> map = asfExtOptions.getExtra();
        writeExtra(writer, project, "Extra ASFExt vocabulary.", map, asfExtOptions.getXmlnsPrefix());
    }

    /**
     * Write the ASF implements.
     *
     * @param writer not null
     * @see <a href="http://svn.apache.org/repos/asf/infrastructure/site-tools/trunk/projects/asfext">
     *      http://svn.apache.org/repos/asf/infrastructure/site-tools/trunk/projects/asfext</a>
     * @see <a href="http://projects.apache.org/docs/standards.html">http://projects.apache.org/docs/standards.html</a>
     */
    private void writeASFImplements(XMLWriter writer) {
        if (asfExtOptions.getStandards() == null || asfExtOptions.getStandards().isEmpty()) {
            return;
        }

        for (Standard standard : asfExtOptions.getStandards()) {
            DoapUtil.writeStartElement(writer, asfExtOptions.getXmlnsPrefix(), "implements");
            DoapUtil.writeStartElement(writer, asfExtOptions.getXmlnsPrefix(), "Standard");

            if (StringUtils.isEmpty(standard.getTitle())) {
                messages.addMessage(
                        new String[] {"asfExtOptions", "standards", "title"}, null, UserMessages.REQUIRED_BY_ASF);
            } else {
                DoapUtil.writeElement(
                        writer,
                        asfExtOptions.getXmlnsPrefix(),
                        "title",
                        standard.getTitle().trim());
            }

            if (StringUtils.isEmpty(standard.getBody())) {
                messages.addMessage(
                        new String[] {"asfExtOptions", "standards", "body"}, null, UserMessages.REQUIRED_BY_ASF);
            } else {
                DoapUtil.writeElement(
                        writer,
                        asfExtOptions.getXmlnsPrefix(),
                        "body",
                        standard.getBody().trim());
            }

            if (StringUtils.isEmpty(standard.getId())) {
                messages.addMessage(
                        new String[] {"asfExtOptions", "standards", "id"}, null, UserMessages.REQUIRED_BY_ASF);
            } else {
                DoapUtil.writeElement(
                        writer,
                        asfExtOptions.getXmlnsPrefix(),
                        "id",
                        standard.getId().trim());
            }

            if (StringUtils.isNotEmpty(standard.getUrl())) {
                String standardUrl = standard.getUrl().trim();
                try {
                    new URL(standardUrl);

                    DoapUtil.writeElement(writer, asfExtOptions.getXmlnsPrefix(), "url", standardUrl);
                } catch (MalformedURLException e) {
                    messages.addMessage(
                            new String[] {"asfExtOptions", "standards", "url"}, standardUrl, UserMessages.INVALID_URL);
                }
            }

            writer.endElement(); // asfext:Standard
            writer.endElement(); // asfext:implements
        }
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
     * @see #writeContributor(XMLWriter, Object, String)
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
                    messages.errorMessages.add(
                            "The organization URL " + organization.getUrl() + " is not a valid URL.");
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
     * Write extra for DOAP or any extension.
     *
     * @param writer      not null
     * @param project     not null
     * @param comment     not null
     * @param map         not null
     * @param xmlnsPrefix not null
     * @since 1.1
     */
    private void writeExtra(
            XMLWriter writer, Project project, String comment, Map<Object, String> map, String xmlnsPrefix) {
        if (map == null || map.isEmpty()) {
            return;
        }

        boolean addComment = false;
        for (Map.Entry<Object, String> entry : map.entrySet()) {
            String key = (String) entry.getKey();
            String value = entry.getValue();

            if (value == null) {
                continue;
            }

            String interpolatedValue = DoapUtil.interpolate(value, project, session.getSettings());
            if (interpolatedValue == null) {
                continue;
            }

            if (!addComment) {
                DoapUtil.writeComment(writer, comment);
                addComment = true;
            }

            try {
                new URL(interpolatedValue);

                DoapUtil.writeRdfResourceElement(writer, xmlnsPrefix, key, interpolatedValue);
            } catch (MalformedURLException e) {
                DoapUtil.writeElement(writer, xmlnsPrefix, key, interpolatedValue);
            }
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
            writeExtra(writer, project, "Other extension vocabulary.", map, extOption.getXmlnsPrefix());
        }
    }

    // ----------------------------------------------------------------------
    // Static methods
    // ----------------------------------------------------------------------

    /**
     * Convenience method that return true is the defined <code>SCM repository</code> is a known provider.
     * <p>
     * Actually, we fully support SVN by the maven-scm-providers component.
     * </p>
     *
     * @param scmRepository a SCM repository
     * @param scmProvider   a SCM provider name
     * @return true if the provider of the given SCM repository is equal to the given scm provider
     * @see <a href="http://svn.apache.org/repos/asf/maven/scm/trunk/maven-scm-providers/">maven-scm-providers</a>
     */
    private static boolean isScmSystem(ScmRepository scmRepository, String scmProvider) {
        if (scmProvider == null || scmProvider.isEmpty()) {
            return false;
        }

        if (scmRepository != null && scmProvider.equalsIgnoreCase(scmRepository.getProvider())) {
            return true;
        }

        return false;
    }

    /**
     * Sort Contributor by name or Developer by id.
     *
     * @param contributors not null
     * @since 1.1
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void sortContributors(List contributors) {
        Collections.sort(contributors, new Comparator<Contributor>() {
            public int compare(Contributor contributor1, Contributor contributor2) {
                if (contributor1 == contributor2) {
                    return 0;
                }

                if (contributor1 == null && contributor2 != null) {
                    return -1;
                }
                if (contributor1 != null && contributor2 == null) {
                    return +1;
                }

                if (Developer.class.isAssignableFrom(contributor1.getClass())
                        && Developer.class.isAssignableFrom(contributor2.getClass())) {
                    Developer developer1 = (Developer) contributor1;
                    Developer developer2 = (Developer) contributor2;

                    if (developer1.getId() == null && developer2.getId() != null) {
                        return -1;
                    }
                    if (developer1.getId() != null && developer2.getId() == null) {
                        return +1;
                    }

                    return developer1.getId().compareTo(developer2.getId());
                }

                if (contributor1.getName() == null && contributor2.getName() != null) {
                    return -1;
                }
                if (contributor1.getName() != null && contributor2.getName() == null) {
                    return +1;
                }
                return contributor1.getName().compareTo(contributor2.getName());
            }
        });
    }

    /**
     * Encapsulates all user messages.
     *
     * @since 1.1
     */
    private class UserMessages {
        public static final int REQUIRED = 10;

        public static final int REQUIRED_BY_ASF_OR_RECOMMENDED = 11;

        public static final int REQUIRED_BY_ASF = 12;

        public static final int RECOMMENDED = 20;

        public static final int INVALID_URL = 30;

        public static final int INVALID_DATE = 31;

        public static final int INVALID_ISO_DATE = 32;

        public static final int INVALID_EMAIL = 33;

        public static final int SHORT_DESC_TOO_LONG = 34;

        private List<String> errorMessages = new ArrayList<>();

        private List<String> warnMessages = new ArrayList<>();

        /**
         * @return the error messages
         */
        public List<String> getErrorMessages() {
            return errorMessages;
        }

        /**
         * @return the warn messages
         */
        public List<String> getWarnMessages() {
            return warnMessages;
        }

        /**
         * @param tags    not null
         * @param value   could be null
         * @param errorId positive id
         */
        protected void addMessage(String[] tags, String value, int errorId) {
            if (tags == null) {
                throw new IllegalArgumentException("tags is required");
            }

            boolean isPom = false;
            if (tags[0].equalsIgnoreCase("project")) {
                isPom = true;
            }
            switch (errorId) {
                case REQUIRED:
                    errorMessages.add("A " + toConfiguration(tags, null) + "  parameter is required.");
                    break;
                case REQUIRED_BY_ASF_OR_RECOMMENDED:
                    if (isPom) {
                        if (asfExtOptions.isIncluded()) {
                            errorMessages.add("A POM " + toConfiguration(tags, null) + " value is required by ASF.");
                        } else {
                            warnMessages.add("No POM " + toConfiguration(tags, null)
                                    + " value is defined, it is highly recommended to have one.");
                        }
                    } else {
                        if (asfExtOptions.isIncluded()) {
                            errorMessages.add("A " + toConfiguration(tags, null) + " parameter is required by ASF.");
                        } else {
                            warnMessages.add("No " + toConfiguration(tags, null)
                                    + " parameter defined, it is highly recommended to have one.");
                        }
                    }
                    break;
                case REQUIRED_BY_ASF:
                    if (isPom) {
                        errorMessages.add("A POM " + toConfiguration(tags, null) + " value is required by ASF.");
                    } else {
                        errorMessages.add("A " + toConfiguration(tags, null) + " parameter is required by ASF.");
                    }
                    break;
                case RECOMMENDED:
                    warnMessages.add("No " + toConfiguration(tags, null)
                            + " parameter defined, it is highly recommended to have one.");
                    break;
                case INVALID_URL:
                    if (isPom) {
                        errorMessages.add("The POM " + toConfiguration(tags, value) + " value is not a valid URL.");
                    } else {
                        errorMessages.add("The " + toConfiguration(tags, value) + " parameter is not a valid URL.");
                    }
                    break;
                case INVALID_DATE:
                    errorMessages.add("The " + toConfiguration(tags, value) + " parameter should be in YYYY-MM-DD.");
                    break;
                case INVALID_EMAIL:
                    errorMessages.add("The POM " + toConfiguration(tags, value) + " value is not a valid email.");
                    break;
                case INVALID_ISO_DATE:
                    errorMessages.add(
                            "The " + toConfiguration(tags, value) + " parameter is not a valid ISO language.");
                    break;
                case SHORT_DESC_TOO_LONG:
                    errorMessages.add("The " + toConfiguration(tags, value)
                            + " first sentence is too long maximum words number is 10.");
                    break;
                default:
                    throw new IllegalArgumentException("Unknown errorId=" + errorId);
            }
        }

        /**
         * @param tags  not null
         * @param value of the last tag, could be null
         * @return the XML configuration defined in tags
         */
        protected String toConfiguration(String[] tags, String value) {
            if (tags == null) {
                throw new IllegalArgumentException("tags is required");
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tags.length; i++) {
                if (i == tags.length - 1 && (value == null || value.isEmpty())) {
                    sb.append("<").append(tags[i]).append("/>");
                } else {
                    sb.append("<").append(tags[i]).append(">");
                }
            }
            if (value != null && !value.isEmpty()) {
                sb.append(value);
            }
            for (int i = tags.length - 1; i >= 0; i--) {
                if (!(i == tags.length - 1 && (value == null || value.isEmpty()))) {
                    sb.append("</").append(tags[i]).append(">");
                }
            }

            return sb.toString();
        }
    }
}
