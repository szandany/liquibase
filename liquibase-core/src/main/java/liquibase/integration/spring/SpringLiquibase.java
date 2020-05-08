package liquibase.integration.spring;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.configuration.ConfigurationProperty;
import liquibase.configuration.GlobalConfiguration;
import liquibase.configuration.LiquibaseConfiguration;
import liquibase.database.Database;
import liquibase.database.DatabaseConnection;
import liquibase.database.DatabaseFactory;
import liquibase.database.OfflineConnection;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.logging.LogService;
import liquibase.logging.LogType;
import liquibase.logging.Logger;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;
import liquibase.util.StringUtils;
import liquibase.util.file.FilenameUtils;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;

import javax.sql.DataSource;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * A Spring-ified wrapper for Liquibase.
 * <p/>
 * Example Configuration:
 * <p/>
 * <p/>
 * This Spring configuration example will cause liquibase to run automatically when the Spring context is
 * initialized. It will load <code>db-changelog.xml</code> from the classpath and apply it against
 * <code>myDataSource</code>.
 * <p/>
 * <p/>
 *
 * <pre>
 * &lt;bean id=&quot;myLiquibase&quot;
 *          class=&quot;liquibase.spring.SpringLiquibase&quot;
 *          &gt;
 *
 *      &lt;property name=&quot;dataSource&quot; ref=&quot;myDataSource&quot; /&gt;
 *
 *      &lt;property name=&quot;changeLog&quot; value=&quot;classpath:db-changelog.xml&quot; /&gt;
 *
 * &lt;/bean&gt;
 *
 * </pre>
 *
 * @author Rob Schoening
 */
public class SpringLiquibase implements InitializingBean, BeanNameAware, ResourceLoaderAware {

    protected final Logger log = LogService.getLog(SpringLiquibase.class);
    protected String beanName;

	protected ResourceLoader resourceLoader;

	protected DataSource dataSource;
	protected String changeLog;
	protected String contexts;
    protected String labels;
    protected String tag;
	protected Map<String, String> parameters;
	protected String defaultSchema;
	protected String liquibaseSchema;
	protected String databaseChangeLogTable;
	protected String databaseChangeLogLockTable;
	protected String liquibaseTablespace;
	protected boolean dropFirst;
	protected boolean clearCheckSums;
	protected boolean shouldRun = true;
	protected File rollbackFile;

	/**
     * Ignores classpath prefix during changeset comparison.
     * This is particularly useful if Liquibase is run in different ways.
     *
     * For instance, if Maven plugin is used to run changesets, as in:
     * <code>
     *      &lt;configuration&gt;
     *          ...
     *          &lt;changeLogFile&gt;path/to/changelog&lt;/changeLogFile&gt;
     *      &lt;/configuration&gt;
     * </code>
     *
     * And {@link SpringLiquibase} is configured like:
     * <code>
     *     SpringLiquibase springLiquibase = new SpringLiquibase();
     *     springLiquibase.setChangeLog("classpath:path/to/changelog");
     * </code>
     *
     * or, in equivalent XML configuration:
     * <code>
     *     &lt;bean id="springLiquibase" class="liquibase.integration.spring.SpringLiquibase"&gt;
     *         &lt;property name="changeLog" value="path/to/changelog" /&gt;
     *      &lt;/bean&gt;
     * </code>
     *
     * {@link Liquibase#listUnrunChangeSets(Contexts, )} will
     * always, by default, return changesets, regardless of their
     * execution by Maven.
     * Maven-executed changeset path name are not be prepended by
     * "classpath:" whereas the ones parsed via SpringLiquibase are.
     *
     * To avoid this issue, just set ignoreClasspathPrefix to true.
     */
    private boolean ignoreClasspathPrefix = true;

	protected boolean testRollbackOnUpdate = false;

	public SpringLiquibase() {
		super();
	}

	public boolean isDropFirst() {
		return dropFirst;
	}

	public void setDropFirst(boolean dropFirst) {
		this.dropFirst = dropFirst;
	}

	public boolean isClearCheckSums() {
		return clearCheckSums;
	}

	public void setClearCheckSums(boolean clearCheckSums) {
		this.clearCheckSums = clearCheckSums;
	}

	public void setShouldRun(boolean shouldRun) {
		this.shouldRun = shouldRun;
	}

	public String getDatabaseProductName() throws DatabaseException {
		Connection connection = null;
        Database database = null;
		String name = "unknown";
		try {
			connection = getDataSource().getConnection();
			database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
			name = database.getDatabaseProductName();
		} catch (SQLException e) {
			throw new DatabaseException(e);
		} finally {
            if (database != null) {
                database.close();
            } else if (connection != null) {
				try {
					if (!connection.getAutoCommit()) {
						connection.rollback();
					}
					connection.close();
                } catch (SQLException e) {
					log.warning(LogType.LOG, "problem closing connection", e);
				}
			}
		}
		return name;
	}

	/**
	 * The DataSource that liquibase will use to perform the migration.
	 */
	public DataSource getDataSource() {
		return dataSource;
	}

	/**
	 * The DataSource that liquibase will use to perform the migration.
	 */
	public void setDataSource(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * Returns a Resource that is able to resolve to a file or classpath resource.
	 */
	public String getChangeLog() {
		return changeLog;
	}

	/**
	 * Sets a Spring Resource that is able to resolve to a file or classpath resource.
	 * An example might be <code>classpath:db-changelog.xml</code>.
	 */
	public void setChangeLog(String dataModel) {

		this.changeLog = dataModel;
	}

	public String getContexts() {
		return contexts;
	}

	public void setContexts(String contexts) {
		this.contexts = contexts;
	}

    public String getLabels() {
        return labels;
    }

    public void setLabels(String labels) {
        this.labels = labels;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getDefaultSchema() {
		return defaultSchema;
	}

	public void setDefaultSchema(String defaultSchema) {
		this.defaultSchema = defaultSchema;
	}

    public String getLiquibaseTablespace() {
        return liquibaseTablespace;
    }

    public void setLiquibaseTablespace(String liquibaseTablespace) {
        this.liquibaseTablespace = liquibaseTablespace;
    }

    public String getLiquibaseSchema() {
        return liquibaseSchema;
    }

    public void setLiquibaseSchema(String liquibaseSchema) {
        this.liquibaseSchema = liquibaseSchema;
    }

    public String getDatabaseChangeLogTable() {
        return databaseChangeLogTable;
    }

    public void setDatabaseChangeLogTable(String databaseChangeLogTable) {
        this.databaseChangeLogTable = databaseChangeLogTable;
    }

    public String getDatabaseChangeLogLockTable() {
        return databaseChangeLogLockTable;
    }

	public void setDatabaseChangeLogLockTable(String databaseChangeLogLockTable) {
		this.databaseChangeLogLockTable = databaseChangeLogLockTable;
	}

	/**
	 * Returns whether a rollback should be tested at update time or not.
	 */
	public boolean isTestRollbackOnUpdate() {
		return testRollbackOnUpdate;
	}

	/**
	 * If testRollbackOnUpdate is set to true a rollback will be tested at tupdate time.
	 * For doing so when the update is performed
	 * @param testRollbackOnUpdate
     */
	public void setTestRollbackOnUpdate(boolean testRollbackOnUpdate) {
		this.testRollbackOnUpdate = testRollbackOnUpdate;
	}

	/**
	 * Executed automatically when the bean is initialized.
	 */
	@Override
    public void afterPropertiesSet() throws LiquibaseException {
        ConfigurationProperty shouldRunProperty = LiquibaseConfiguration.getInstance()
            .getProperty(GlobalConfiguration.class, GlobalConfiguration.SHOULD_RUN);

		if (!shouldRunProperty.getValue(Boolean.class)) {
            LogService.getLog(getClass()).info(LogType.LOG, "Liquibase did not run because " + LiquibaseConfiguration
                .getInstance().describeValueLookupLogic(shouldRunProperty) + " was set to false");
            return;
		}
		if (!shouldRun) {
            LogService.getLog(getClass()).info(LogType.LOG, "Liquibase did not run because 'shouldRun' " + "property was set " +
                "to false on " + getBeanName() + " Liquibase Spring bean.");
            return;
		}

		Connection c = null;
		Liquibase liquibase = null;
		try {
			c = getDataSource().getConnection();
            liquibase = createLiquibase(c);
			generateRollbackFile(liquibase);
			performUpdate(liquibase);
		} catch (SQLException e) {
			throw new DatabaseException(e);
		} finally {
			Database database = null;
			if (liquibase != null) {
				database = liquibase.getDatabase();
			}
			if (database != null) {
                database.close();
            }
        }

	}

    private void generateRollbackFile(Liquibase liquibase) throws LiquibaseException {
        if (rollbackFile != null) {

            try (
                FileOutputStream fileOutputStream = new FileOutputStream(rollbackFile);
                Writer output = new OutputStreamWriter(fileOutputStream, LiquibaseConfiguration.getInstance()
                    .getConfiguration(GlobalConfiguration.class).getOutputEncoding())

            ) {

                if (tag != null) {
                    liquibase.futureRollbackSQL(tag, new Contexts(getContexts()),
                        new LabelExpression(getLabels()), output);
                } else {
                    liquibase.futureRollbackSQL(new Contexts(getContexts()), new LabelExpression(getLabels()), output);
                }
            } catch (IOException e) {
                throw new LiquibaseException("Unable to generate rollback file.", e);
            }
        }
    }

    protected void performUpdate(Liquibase liquibase) throws LiquibaseException {
		if (isClearCheckSums()) {
			liquibase.clearCheckSums();
		}

		if (isTestRollbackOnUpdate()) {
			if (tag != null) {
				liquibase.updateTestingRollback(tag, new Contexts(getContexts()), new LabelExpression(getLabels()));
			} else {
				liquibase.updateTestingRollback(new Contexts(getContexts()), new LabelExpression(getLabels()));
			}
		} else {
			if (tag != null) {
				liquibase.update(tag, new Contexts(getContexts()), new LabelExpression(getLabels()));
			} else {
				liquibase.update(new Contexts(getContexts()), new LabelExpression(getLabels()));
			}
		}
    }

	protected Liquibase createLiquibase(Connection c) throws LiquibaseException {
		SpringResourceOpener resourceAccessor = createResourceOpener();
		Liquibase liquibase = new Liquibase(getChangeLog(), resourceAccessor, createDatabase(c, resourceAccessor));
        liquibase.setIgnoreClasspathPrefix(isIgnoreClasspathPrefix());
		if (parameters != null) {
			for (Map.Entry<String, String> entry : parameters.entrySet()) {
				liquibase.setChangeLogParameter(entry.getKey(), entry.getValue());
			}
		}

		if (isDropFirst()) {
			liquibase.dropAll();
		}

		return liquibase;
	}

	/**
	 * Subclasses may override this method add change some database settings such as
	 * default schema before returning the database object.
	 *
	 * @param c
	 * @return a Database implementation retrieved from the {@link DatabaseFactory}.
	 * @throws DatabaseException
	 */
	protected Database createDatabase(Connection c, ResourceAccessor resourceAccessor) throws DatabaseException {

        DatabaseConnection liquibaseConnection;
        if (c == null) {
            log.warning(LogType.LOG,
                "Null connection returned by liquibase datasource. Using offline unknown database");
            liquibaseConnection = new OfflineConnection("offline:unknown", resourceAccessor);

        } else {
            liquibaseConnection = new JdbcConnection(c);
        }

        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(liquibaseConnection);
		if (StringUtils.trimToNull(this.defaultSchema) != null) {
            if (database.supportsSchemas()) {
                database.setDefaultSchemaName(this.defaultSchema);
            } else if (database.supportsCatalogs()) {
                database.setDefaultCatalogName(this.defaultSchema);
            }
        }
        if (StringUtils.trimToNull(this.liquibaseSchema) != null) {
            if (database.supportsSchemas()) {
                database.setLiquibaseSchemaName(this.liquibaseSchema);
            } else if (database.supportsCatalogs()) {
                database.setLiquibaseCatalogName(this.liquibaseSchema);
            }
        }
        if (StringUtils.trimToNull(this.liquibaseTablespace) != null && database.supportsTablespaces()) {
            database.setLiquibaseTablespaceName(this.liquibaseTablespace);
        }
        if (StringUtils.trimToNull(this.databaseChangeLogTable) != null) {
            database.setDatabaseChangeLogTableName(this.databaseChangeLogTable);
        }
        if (StringUtils.trimToNull(this.databaseChangeLogLockTable) != null) {
            database.setDatabaseChangeLogLockTableName(this.databaseChangeLogLockTable);
        }
		return database;
	}

	public void setChangeLogParameters(Map<String, String> parameters) {
		this.parameters = parameters;
	}

	/**
	 * Create a new resourceOpener.
	 */
	protected SpringResourceOpener createResourceOpener() {
		return new SpringResourceOpener(getChangeLog());
	}

	/**
	 * Gets the Spring-name of this instance.
	 *
	 * @return
	 */
	public String getBeanName() {
		return beanName;
	}

    /**
     * Spring sets this automatically to the instance's configured bean name.
     */
    @Override
    public void setBeanName(String name) {
        this.beanName = name;
    }

    public ResourceLoader getResourceLoader() {
        return resourceLoader;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

	public void setRollbackFile(File rollbackFile) {
		this.rollbackFile = rollbackFile;
    }

    public boolean isIgnoreClasspathPrefix() {
        return ignoreClasspathPrefix;
    }

    public void setIgnoreClasspathPrefix(boolean ignoreClasspathPrefix) {
        this.ignoreClasspathPrefix = ignoreClasspathPrefix;
	}

	@Override
    public String toString() {
        return getClass().getName() + "(" + this.getResourceLoader().toString() + ")";
    }

    public class SpringResourceOpener extends ClassLoaderResourceAccessor {

        private String parentFile;

        public SpringResourceOpener(String parentFile) {
            this.parentFile = parentFile;
        }

        @Override
        protected void init() {
            super.init();
            try {
                final ResourcePatternResolver resolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
                for (final String ending : new String[]{"xml", "yml", "yaml"}) {
                    Resource[] resources = resolver.getResources("classpath*:**/*." + ending);
                    for (Resource resource : resources) {
                        String url = resource.getURL().toExternalForm();
                        if (!url.startsWith("jar:")) {
                            continue;
                        }
                        url = url.substring(4, url.indexOf('!'));
                        url = url.replace("\\", "/");
                        if (url.startsWith("file:") && url.charAt(5) != '/') {
                            url = "file:/" + url.substring(5) + "/";
                        }
                        if (!getRootPaths().contains(url)) {
                            addRootPath(new URL(url));
                        }
                    }
                }
            } catch (IOException e) {
                LogService.getLog(getClass()).warning(LogType.LOG, "Error initializing SpringLiquibase", e);
            }
        }

        @Override
        public Set<String> list(String relativeTo, String path, boolean includeFiles, boolean includeDirectories,
                                boolean recursive) throws IOException {
            if (path == null) {
                return null;
            }

            //
            // Possible Resources Types
            //

            // Standalone Jar
            // Root Path: jar:file:/Projects/my-project/second-module/target/second-module-1.0.0-SNAPSHOT-exec.jar!/BOOT-INF/lib/first-module-1.0.0-SNAPSHOT.jar!/
            // +Resource: jar:file:/Projects/my-project/second-module/target/second-module-1.0.0-SNAPSHOT-exec.jar!/BOOT-INF/lib/first-module-1.0.0-SNAPSHOT.jar!/db/changelog/0-initial-schema.xml

            // Standalone War
            // Root Path: jar:file:/Projects/my-project/second-module/target/second-module-1.0.0-SNAPSHOT-exec.war!/WEB-INF/lib/first-module-1.0.0-SNAPSHOT.jar!/
            // +Resource: jar:file:/Projects/my-project/second-module/target/second-module-1.0.0-SNAPSHOT-exec.war!/WEB-INF/lib/first-module-1.0.0-SNAPSHOT.jar!/-db/changelog/0-initial-schema.xml

            // Openned Jar Dependency
            // Root Path: file:/Projects/my-project/first-module/target/classes/
            // +Resource: file:/Projects/my-project/first-module/target/classes/db/changelog/0-initial-schema.xml

            // War Wild-Fly Exploded
            // Root Path: vfs:/Projects/my-project/second-module/target/second-module-1.0.0-SNAPSHOT/WEB-INF/lib/first-module-1.0.0-SNAPSHOT.jar/
            // +Resource: vfs:/Projects/my-project/second-module/target/second-module-1.0.0-SNAPSHOT/WEB-INF/lib/first-module-1.0.0-SNAPSHOT.jar/db/changelog/0-initial-schema.xml

            // War Wild-Fly Artifact
            // Root Path: vfs:/content/second-module-1.0.0-SNAPSHOT.war/WEB-INF/lib/first-module-1.0.0-SNAPSHOT.jar/
            // +Resource: vfs:/content/second-module-1.0.0-SNAPSHOT.war/WEB-INF/lib/first-module-1.0.0-SNAPSHOT.jar/db/changelog/0-initial-schema.xml

            Set<String> returnSet = new HashSet<>();
            path = path + (recursive ? "**" : '*'); // All files inside!
            String tempFile = FilenameUtils.concat(FilenameUtils.getFullPath(relativeTo), path);

            Resource[] resources = getResources(adjustClasspath(tempFile));
            for (Resource resource : resources) {
                String resourceStr = resource.getURL().toExternalForm();
                String resourcePath = convertToPath(resourceStr);
                if (resourceStr.endsWith(resourcePath) && !resourceStr.equals(resourcePath)) {
                    returnSet.add(resourcePath);
                } else {
                    // Closed Jar Dependency
                    // Root Path:     file:/.m2/repository/org/liquibase/test/first-module/1.0.0-SNAPSHOT/first-module-1.0.0-SNAPSHOT.jar/
                    // +Resource: jar:file:/.m2/repository/org/liquibase/test/first-module/1.0.0-SNAPSHOT/first-module-1.0.0-SNAPSHOT.jar!/db/changelog/0-initial-schema.xml

                    String newResourceStr = resource.getURL().getFile(); // Remove "jar:" from begining.
                    newResourceStr = newResourceStr.replaceAll("!", "");
                    String newResourcePath = convertToPath(newResourceStr);
                    if (newResourceStr.endsWith(newResourcePath) && !newResourceStr.equals(newResourcePath)) {
                        returnSet.add(newResourcePath);
                    } else {
                        LogService.getLog(getClass()).warning(
                            LogType.LOG, "Not a valid resource entry: " + resourceStr);
                    }
                }
            }

            return returnSet;
        }

        @Override
        public Set<InputStream> getResourcesAsStream(String path) throws IOException {
            if (path == null) {
                return null;
            }
            Resource[] resources = getResources(adjustClasspath(path));

            if ((resources == null) || (resources.length == 0)) {
                return null;
            }

            Set<InputStream> returnSet = new HashSet<>();
            for (Resource resource : resources) {
                LogService.getLog(getClass()).debug(LogType.LOG, "Opening "
                    + resource.getURL().toExternalForm() + " as " + path);
                URLConnection connection = resource.getURL().openConnection();
                connection.setUseCaches(false);
                returnSet.add(connection.getInputStream());
            }

            return returnSet;
        }

        public Resource getResource(String file) {
            return getResourceLoader().getResource(adjustClasspath(file));
        }

        private String adjustClasspath(String file) {
            if (file == null) {
                return null;
            }
            return (isPrefixPresent(parentFile) && !isPrefixPresent(file)) ? (ResourceLoader.CLASSPATH_URL_PREFIX +
                file) : file;
        }


        private Resource[] getResources(String foundPackage) throws IOException {
            return ResourcePatternUtils.getResourcePatternResolver(getResourceLoader()).getResources(foundPackage);
        }

        private Set<String> getPackagesFromManifest(Resource manifest) throws IOException {
            Set<String> manifestPackages = new HashSet<>();
            if (!manifest.exists()) {
                return manifestPackages;
            }
            InputStream inputStream = null;
            try {
                inputStream = manifest.getInputStream();
                Manifest manifestObj = new Manifest(inputStream);
                Attributes attributes = manifestObj.getAttributes("Liquibase-Package");
                if (attributes == null) {
                    return manifestPackages;
                }
                for (Object attr : attributes.values()) {
                    String packages = "\\s*,\\s*";
                    for (String fullPackage : attr.toString().split(packages)) {
                        manifestPackages.add(fullPackage.split("\\.")[0]);
                    }
                }
            } finally {
                if (inputStream != null) {
                    inputStream.close();
                }
            }
            return manifestPackages;
        }

        public boolean isPrefixPresent(String file) {
            if (file == null) {
                return false;
            }
            return file.startsWith("classpath") || file.startsWith("file:") || file.startsWith("url:");
        }

        @Override
        public ClassLoader toClassLoader() {
            return getResourceLoader().getClassLoader();
        }
    }
}
