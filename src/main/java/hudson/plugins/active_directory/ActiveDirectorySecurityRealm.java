/*
 * The MIT License
 *
 * Copyright (c) 2008-2014, Kohsuke Kawaguchi, CloudBees, Inc., and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.active_directory;

import com4j.typelibs.ado20.ClassFactory;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Functions;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.security.AbstractPasswordBasedSecurityRealm;
import hudson.security.AuthorizationStrategy;
import hudson.security.GroupDetails;
import hudson.security.SecurityRealm;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jakarta.servlet.ServletException;
import jenkins.model.Jenkins;
import jenkins.security.FIPS140;
import jenkins.security.SecurityListener;
import jenkins.util.SystemProperties;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.StartTlsRequest;
import javax.naming.ldap.StartTlsResponse;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.Util.*;

/**
 * {@link SecurityRealm} that talks to Active Directory.
 * 
 * @author Kohsuke Kawaguchi
 */
public class ActiveDirectorySecurityRealm extends AbstractPasswordBasedSecurityRealm {

    @Restricted(NoExternalUse.class)
    static final String LEGACY_FORCE_LDAPS_PROPERTY = ActiveDirectorySecurityRealm.class.getName()+".forceLdaps";

    /**
     * Represent the old Active Directory Domain
     *
     * <p>
     * We need to keep this as transient in order to be able to use readResolve
     * to migrate the old descriptor to the newone.
     *
     * <p>
     * This has been deprecated since {@link ActiveDirectoryDomain}
     */
    public transient String domain;

    /**
     * Represent the old Active Directory Domain Controllers
     *
     * <p>
     * We need to keep this as transient in order to be able to use readResolve
     * to migrate the old descriptor to the newone.
     *
     * <p>
     * This has been deprecated since {@link ActiveDirectoryDomain}
     */
    public transient String server;

    /**
     * List of {@link ActiveDirectoryDomain}
     *
     */
    public List<ActiveDirectoryDomain> domains;

    /**
     * Active directory site (which specifies the physical concentration of the
     * servers), if any. If the value is non-null, we'll only contact servers in
     * this site.
     * 
     * <p>
     * On Windows, I'm assuming ADSI takes care of everything automatically.
     *
     * <p>
     * We need to keep this as transient in order to be able to use readResolve
     * to migrate the old descriptor to the newone.
     */
    public transient final String site;

    /**
     * Represent the old Name
     *
     * <p>
     * We need to keep this as transient in order to be able to use readResolve
     * to migrate the old descriptor to the new one.
     *
     * <p>
     * This has been deprecated @since Jenkins 2.1
     */
    public transient String bindName;

    /**
     * Represent the old bindPassword
     *
     * <p>
     * We need to keep this as transient in order to be able to use readResolve
     * to migrate the old descriptor to the new one.
     *
     * <p>
     * This has been deprecated @since Jenkins 2.1
     */
    public transient Secret bindPassword;

    /**
     * If true enable startTls in case plain communication is used. In case the plugin
     * is configured to use TLS then this option will not have any impact.
     * @see #getRequireTLS()
     */
    public Boolean startTls;

    /**
     * If true uses ensures that the ldap connection is encrypted with TLS (or ssl).
     * Takes precedence over requireTLS when set to {@code Boolean.TRUE}
     * is configured to use TLS then this option will not have any impact.
     */
    private Boolean requireTLS = Boolean.TRUE;

    private GroupLookupStrategy groupLookupStrategy;

    /**
     * If true, Jenkins ignores Active Directory groups that are not being used by the active Authorization Strategy.
     * This can significantly improve performance in environments with a large number of groups
     * but a small number of corresponding rules defined by the Authorization Strategy.
     * Groups are considered as used if they are returned by {@link AuthorizationStrategy#getGroups()}.
     */
    public final boolean removeIrrelevantGroups;

    /**
     *  Cache of the Active Directory plugin
     */
    protected CacheConfiguration cache;

    /**
     *  Ldap extra properties
     */
    protected List<EnvironmentProperty> environmentProperties;

    /**
     * Selects the SSL strategy to follow on the TLS connections
     *
     * <p>
     *     Even if we are not using any of the TLS ports (3269/636) the plugin will try to establish a TLS channel
     *     using startTLS. Because of this, we need to be able to specify the SSL strategy on the plugin
     *
     * <p>
     *     For the moment there are two possible values: trustAllCertificates and trustStore.
     */
    @Deprecated
    protected transient TlsConfiguration tlsConfiguration;

    /**
     *  The Jenkins internal user to fall back in case f {@link NamingException}
     */
    protected ActiveDirectoryInternalUsersDatabase internalUsersDatabase;

    private transient AbstractActiveDirectoryAuthenticationProvider authenticationProvider;

    public ActiveDirectorySecurityRealm(String domain, String site, String bindName, String bindPassword, String server) {
        this(domain, site, bindName, bindPassword, server, GroupLookupStrategy.AUTO, false);
    }

    public ActiveDirectorySecurityRealm(String domain, String site, String bindName, String bindPassword, String server, GroupLookupStrategy groupLookupStrategy) {
        this(domain,site,bindName,bindPassword,server,groupLookupStrategy,false);
    }

    public ActiveDirectorySecurityRealm(String domain, String site, String bindName,
                                        String bindPassword, String server, GroupLookupStrategy groupLookupStrategy, boolean removeIrrelevantGroups) {
        this(domain, site, bindName, bindPassword, server, groupLookupStrategy, removeIrrelevantGroups, null);
    }

    public ActiveDirectorySecurityRealm(String domain, String site, String bindName,
                                        String bindPassword, String server, GroupLookupStrategy groupLookupStrategy, boolean removeIrrelevantGroups, CacheConfiguration cache) {
        this( domain, Arrays.asList(new ActiveDirectoryDomain(domain, server)), site, bindName, bindPassword, server, groupLookupStrategy, removeIrrelevantGroups, domain!=null, cache, true);
    }

    public ActiveDirectorySecurityRealm(String domain, List<ActiveDirectoryDomain> domains, String site, String bindName,
                                        String bindPassword, String server, GroupLookupStrategy groupLookupStrategy, boolean removeIrrelevantGroups, Boolean customDomain, CacheConfiguration cache, Boolean startTls) {
        this(domain, domains, site, bindName, bindPassword, server, groupLookupStrategy, removeIrrelevantGroups, customDomain, cache, startTls, TlsConfiguration.TRUST_ALL_CERTIFICATES);
    }

    public ActiveDirectorySecurityRealm(String domain, List<ActiveDirectoryDomain> domains, String site, String bindName,
                                        String bindPassword, String server, GroupLookupStrategy groupLookupStrategy, boolean removeIrrelevantGroups, Boolean customDomain, CacheConfiguration cache, Boolean startTls, TlsConfiguration tlsConfiguration) {
        this(domain, domains, site, bindName, bindPassword, server, groupLookupStrategy, removeIrrelevantGroups, customDomain, cache, startTls, tlsConfiguration, null);
    }

    @Deprecated
    public ActiveDirectorySecurityRealm(String domain, List<ActiveDirectoryDomain> domains, String site, String bindName,
                                        String bindPassword, String server, GroupLookupStrategy groupLookupStrategy, boolean removeIrrelevantGroups, Boolean customDomain, CacheConfiguration cache, Boolean startTls, TlsConfiguration tlsConfiguration, ActiveDirectoryInternalUsersDatabase internalUsersDatabase) {
        this(domain, domains, site, bindName, bindPassword, server, groupLookupStrategy, removeIrrelevantGroups, customDomain, cache, startTls, (ActiveDirectoryInternalUsersDatabase) null);
    }

    @Deprecated
    public ActiveDirectorySecurityRealm(String domain, List<ActiveDirectoryDomain> domains, String site, String bindName,
                                        String bindPassword, String server, GroupLookupStrategy groupLookupStrategy, boolean removeIrrelevantGroups, Boolean customDomain, CacheConfiguration cache, Boolean startTls, ActiveDirectoryInternalUsersDatabase internalUsersDatabase) {
        this(domain, domains, site, bindName,bindPassword, server, groupLookupStrategy, removeIrrelevantGroups, customDomain, cache, startTls, internalUsersDatabase, true);
    }

    @DataBoundConstructor
    @SuppressFBWarnings(value = "PA_PUBLIC_PRIMITIVE_ATTRIBUTE", justification = "TODO needs triage")
    // as Java signature, this binding doesn't make sense, so please don't use this constructor
    public ActiveDirectorySecurityRealm(String domain, List<ActiveDirectoryDomain> domains, String site, String bindName,
                                        String bindPassword, String server, GroupLookupStrategy groupLookupStrategy, boolean removeIrrelevantGroups, Boolean customDomain, CacheConfiguration cache, Boolean startTls, ActiveDirectoryInternalUsersDatabase internalUsersDatabase, boolean requireTLS) {
        if (customDomain != null && !customDomain)
            domains = null;
        this.domain = fixEmpty(domain);
        this.server = fixEmpty(server);
        this.domains = domains;
        this.site = fixEmpty(site);
        this.bindName = fixEmpty(bindName);
        this.bindPassword = Secret.fromString(fixEmpty(bindPassword));
        this.groupLookupStrategy = groupLookupStrategy;
        this.removeIrrelevantGroups = removeIrrelevantGroups;
        this.cache = cache;
        this.internalUsersDatabase = internalUsersDatabase;

        // Gives exception if TLS is not used in FIPS mode.
        if (isFipsNonCompliant(requireTLS, startTls)) {
            throw new IllegalArgumentException(Messages.TlsConfiguration_ErrorMessage());
        }
        this.startTls = startTls;
        this.requireTLS = requireTLS;
    }

    @DataBoundSetter
    public void setEnvironmentProperties(List<EnvironmentProperty> environmentProperties) {
        this.environmentProperties = environmentProperties;
    }

    @Restricted(NoExternalUse.class)
    public CacheConfiguration getCache() {
        if (cache != null && (cache.getSize() == 0 || cache.getTtl() == 0)) {
            return null;
        }
        return cache;
    }

    @Restricted(NoExternalUse.class)
    public String getJenkinsInternalUser() {
        return internalUsersDatabase == null ? null : internalUsersDatabase.getJenkinsInternalUser();
    }

    @Restricted(NoExternalUse.class)
    public ActiveDirectoryInternalUsersDatabase getInternalUsersDatabase() {
        return internalUsersDatabase != null && internalUsersDatabase.getJenkinsInternalUser() != null && internalUsersDatabase.getJenkinsInternalUser().isEmpty() ? null : internalUsersDatabase;
    }

    @Restricted(NoExternalUse.class)
    public Boolean isStartTls() {
        return startTls;
    }

    @Restricted(NoExternalUse.class)
    @NonNull
    /**
     * Obtain the Boolean flag showing if the AD Connection shall enforce the use of TLS (plain text connections will not be allowed).
     * Despite returning a {@code Boolean} this will never return {@code null}, it is a Boolean only to match the underlying {@code requireTLS} field, and if that is {@code null} the legacy {@code forceLdaps} property is used.
     * @return {@code Boolean.TRUE} iff the connection to the server requires TLS.
     */
    public Boolean getRequireTLS() {
        if (requireTLS != null) {
            return requireTLS;
        }
        // legacy was forced by a system property prior to SECURITY-1389
        return Boolean.getBoolean(LEGACY_FORCE_LDAPS_PROPERTY);
    }

    @Restricted(NoExternalUse.class)
    public boolean isRequireTLSPersisted() {
        return requireTLS != null;
    }

    public Integer getSize() {
        return cache == null ? null : cache.getSize();
    }

    public Integer getTtl() {
        return cache == null ? null : cache.getTtl();
    }

    // for jelly use only
    @Restricted(NoExternalUse.class)
    public List<EnvironmentProperty> getEnvironmentProperties() {
        return environmentProperties;
    }

    @Restricted(NoExternalUse.class)
    public boolean getCustomDomain() {
        return domains != null;
    }

    public GroupLookupStrategy getGroupLookupStrategy() {
        if (groupLookupStrategy==null)      return GroupLookupStrategy.TOKENGROUPS;
        return groupLookupStrategy;
    }

    // for jelly use only
    @Deprecated
    @Restricted(NoExternalUse.class)
    public TlsConfiguration getTlsConfiguration() {
        return tlsConfiguration;
    }

    @Restricted(NoExternalUse.class)
    public List<ActiveDirectoryDomain> getDomains() {
        return domains;
    }

    /**
     * Get the @link{ActiveDirectoryDomain} given the domain
     *
     * @param domain
     *      The name of the Active Directory domain
     *
     * @return the @link{ActiveDirectoryDomain}
     *          null if not exist.
     */
    public ActiveDirectoryDomain getDomain(String domain) {
        for (ActiveDirectoryDomain activeDirectoryDomain : domains) {
            if (activeDirectoryDomain.getName().equals(domain)) {
                return activeDirectoryDomain;
            }
        }
        return null;
    }

    /**
     * Checks whether Jenkins is running in FIPS mode and TLS is not enabled for communication.
     *
     * @return true if the application is in FIPS mode, requireTls and startTls are false.
     *         <br>
     *         false if either the application is not in FIPS mode or any of requireTls, startTls is true.
     */
    private static boolean isFipsNonCompliant(boolean requireTls, boolean startTls) {
        return FIPS140.useCompliantAlgorithms() && !requireTls && !startTls;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Authentication test.
     */
    @RequirePOST
    public void doAuthTest(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String username, @QueryParameter String password) throws IOException, ServletException {
        // require the administrator permission since this is full of debug info.
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        StringWriter out = new StringWriter();
        PrintWriter pw = new PrintWriter(out);

        ClassLoader ccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            AbstractActiveDirectoryAuthenticationProvider uds = getAuthenticationProvider();
            if (uds instanceof ActiveDirectoryUnixAuthenticationProvider) {
                ActiveDirectoryUnixAuthenticationProvider p = (ActiveDirectoryUnixAuthenticationProvider) uds;
                DescriptorImpl descriptor = getDescriptor();

                for (ActiveDirectoryDomain domain : domains) {
	                try {
	                    pw.println("Domain= " + domain.getName() + " site= "+ domain.getSite());
	                    List<SocketInfo> ldapServers = descriptor.obtainLDAPServer(domain);
	                    pw.println("List of domain controllers: "+ldapServers);
	                    
	                    for (SocketInfo ldapServer : ldapServers) {
	                        pw.println("Trying a domain controller at "+ldapServer);
	                        try {
	                            UserDetails d = p.retrieveUser(username, new ActiveDirectoryUnixAuthenticationProvider.UserPassword(password), domain, Collections.singletonList(ldapServer));
	                            pw.println("Authenticated as "+d);
	                        } catch (AuthenticationException e) {
	                            e.printStackTrace(pw);
	                        }
	                    }
	                } catch (NamingException e) {
	                    pw.println("Failing to resolve domain controllers");
	                    e.printStackTrace(pw);
	                }
                }
            } else {
                pw.println("Using Windows ADSI. No diagnostics available.");
            }
        } catch (Exception e) {
            e.printStackTrace(pw);
        } finally {
            Thread.currentThread().setContextClassLoader(ccl);
        }

        req.setAttribute("output", out.toString());
        req.getView(this, "test.jelly").forward(req, rsp);
    }

    public Object readResolve() throws ObjectStreamException {
        if (domain != null) {
            this.domains = new ArrayList<>();
            domain = domain.trim();
            String[] oldDomains = domain.split(",");
            for (String oldDomain : oldDomains) {
                oldDomain = oldDomain.trim();
                this.domains.add(new ActiveDirectoryDomain(oldDomain, server));
            }
        }
        List<ActiveDirectoryDomain> activeDirectoryDomains = this.getDomains();
        // JENKINS-14281 On Windows domain can be indeed null
        if (activeDirectoryDomains != null) {
            // JENKINS-39375 Support a different bindUser per domain
            if (bindName != null && bindPassword != null) {
                for (ActiveDirectoryDomain activeDirectoryDomain : activeDirectoryDomains) {
                    activeDirectoryDomain.bindName = bindName;
                    activeDirectoryDomain.bindPassword = bindPassword;
                }
            }
            // JENKINS-39423 Make site independent of each domain
            if (site != null) {
                for (ActiveDirectoryDomain activeDirectoryDomain : activeDirectoryDomains) {
                    activeDirectoryDomain.site = site;
                }
            }
            // SECURITY-859 Make tlsConfiguration independent of each domain
            if (tlsConfiguration != null) {
                for (ActiveDirectoryDomain activeDirectoryDomain : activeDirectoryDomains) {
                    activeDirectoryDomain.tlsConfiguration = tlsConfiguration;
                }
            }
        }

        if (startTls == null) {
            this.startTls = true;
        }

        //requireTls can be  null, to avoid null-pointer exception, set flag as false.
        boolean requireTlsFlag = requireTLS == null ? false : requireTLS;

        // Gives exception if TLS is not used in FIPS mode.
        if (isFipsNonCompliant(requireTlsFlag, startTls))
            throw new IllegalArgumentException(Messages.TlsConfiguration_ErrorMessage());
        return this;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SecurityRealm> {
        public String getDisplayName() {
            return Messages.DisplayName();
        }

        @Override
        public String getHelpFile() {
            return "/plugin/active-directory/help/realm.html";
        }

        /**
         * If true, we can do ADSI/COM based look up that's far more reliable.
         * False if we need to do the authentication in pure Java via
         * {@link ActiveDirectoryUnixAuthenticationProvider}
         */
        public boolean canDoNativeAuth() {
            if (!Functions.isWindows())     return false;

            try {
                ClassFactory.createConnection().dispose();
                return true;
            } catch (Throwable t) {
                if (!WARNED) {
                    LOGGER.log(Level.INFO,"COM4J isn't working. Falling back to non-native authentication",t);
                    WARNED = true;
                }
                return false;
            }
        }

        public ListBoxModel doFillSizeItems() {
            ListBoxModel listBoxModel = new ListBoxModel();
            listBoxModel.add("10 elements", "10");
            listBoxModel.add("20 elements", "20");
            listBoxModel.add("50 elements", "50");
            listBoxModel.add("100 elements", "100");
            listBoxModel.add("200 elements", "200");
            listBoxModel.add("256 elements", "256");
            listBoxModel.add("500 elements", "500");
            listBoxModel.add("1000 elements", "1000");
            return listBoxModel;
        }

        public ListBoxModel doFillTtlItems() {
            ListBoxModel listBoxModel = new ListBoxModel();
            listBoxModel.add("30 sec", "30");
            listBoxModel.add("1 min", "60");
            listBoxModel.add("5 min", "300");
            listBoxModel.add("10 min", "600");
            listBoxModel.add("15 min", "900");
            listBoxModel.add("30 min", "1800");
            listBoxModel.add("1 hour", "3600");

            return listBoxModel;
        }

        public ListBoxModel doFillGroupLookupStrategyItems() {
            ListBoxModel model = new ListBoxModel();
            for (GroupLookupStrategy e : GroupLookupStrategy.values()) {
                model.add(e.getDisplayName(),e.name());
            }
            return model;
        }

        public FormValidation doCheckRequireTLS(@QueryParameter boolean requireTLS, @QueryParameter boolean startTls) {
            if (isFipsNonCompliant(requireTLS, startTls)) {
                return FormValidation.error(Messages.TlsConfiguration_ErrorMessage());
            }
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (System.getProperty(ActiveDirectoryAuthenticationProvider.ADSI_FLAGS_SYSTEM_PROPERTY_NAME) != null) {
                return FormValidation.warning("This setting is overridden by the ADSI mode system property");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckStartTls(@QueryParameter boolean requireTLS, @QueryParameter boolean startTls) {
            if (isFipsNonCompliant(requireTLS, startTls)) {
                return FormValidation.error(Messages.TlsConfiguration_ErrorMessage());
            }
            return FormValidation.ok();
        }

        protected static boolean isTrustAllCertificatesEnabled(TlsConfiguration tlsConfiguration) {
            return (tlsConfiguration == null || TlsConfiguration.TRUST_ALL_CERTIFICATES.equals(tlsConfiguration));
        }

        private static boolean WARNED = false;

        @Deprecated
        public DirContext bind(String principalName, String password, List<SocketInfo> ldapServers, Hashtable<String, String> props) throws NamingException {
            return bind(principalName, password, ldapServers, props, TlsConfiguration.TRUST_ALL_CERTIFICATES);
        }

        @Deprecated
        public DirContext bind(String principalName, String password, List<SocketInfo> ldapServers, Hashtable<String, String> props, TlsConfiguration tlsConfiguration) throws NamingException {
            return bind(principalName, password, ldapServers, props, tlsConfiguration, isRequireTLS());
        }

        @Deprecated
        public DirContext bind(String principalName, String password, List<SocketInfo> ldapServers, Hashtable<String, String> props, TlsConfiguration tlsConfiguration, boolean requireTLS) throws NamingException {
            return bind(principalName, password, ldapServers, props, tlsConfiguration, requireTLS, isStartTLS());
        }

            /**
             * Binds to the server using the specified username/password.
             * <p>
             * In a real deployment, often there are servers that don't respond or
             * otherwise broken, so try all the servers.
             */
        public DirContext bind(String principalName, String password, List<SocketInfo> ldapServers, Hashtable<String, String> props, TlsConfiguration tlsConfiguration, boolean requireTLS, boolean startTls) throws NamingException {
            // in a AD forest, it'd be mighty nice to be able to login as "joe"
            // as opposed to "joe@europe",
            // but the bind operation doesn't appear to allow me to do so.
            Hashtable<String, String> newProps = new Hashtable<>();

            // Sometimes might be useful to ignore referral. Use this System property is under the user risk
            Boolean ignoreReferrals = Boolean.valueOf(System.getProperty("hudson.plugins.active_directory.referral.ignore", "false"));

            if (!ignoreReferrals) {
                newProps.put(Context.REFERRAL, "follow");
            } else {
                newProps.put(Context.REFERRAL, "ignore");
            }

            newProps.put("java.naming.ldap.attributes.binary","tokenGroups objectSid");

            if (requireTLS && isTrustAllCertificatesEnabled(tlsConfiguration)) {
                newProps.put("java.naming.ldap.factory.socket", TrustAllSocketFactory.class.getName());
            }

            newProps.putAll(props);
            NamingException namingException = null;

            for (SocketInfo ldapServer : ldapServers) {
                try {
                    LdapContext context = bind(principalName, password, ldapServer, newProps, tlsConfiguration, requireTLS, startTls);
                    LOGGER.fine("Bound to " + ldapServer);
                    return context;
                } catch (javax.naming.AuthenticationException e) {
                    // if the authentication failed (as opposed to a communication problem with the server),
                    // don't retry, because if this is because of a wrong password, we can end up locking
                    // the user out by causing multiple failed attempts.
                    // error code 49 (LdapClient.LDAP_INVALID_CREDENTIALS) maps to this exception in LdapCtx.mapErrorCode
                    // see http://confluence.atlassian.com/display/CONFKB/LDAP+Error+Code+49 and http://www-01.ibm.com/support/docview.wss?uid=swg21290631
                    // for subcodes within this error.
                    // it seems like we can be clever about checking subcode to decide if we retry or not,
                    // but I'm erring on the safe side as I'm not sure how reliable the code is, and maybe
                    // servers can be configured to hide the distinction between "no such user" and "bad password"
                    // to reveal what user names are available.
                    LOGGER.log(Level.WARNING, "Failed to authenticate while binding to "+ldapServer, e);
                    throw new BadCredentialsException("Either no such user '" + principalName + "' or incorrect password", namingException);
                } catch (NamingException e) {
                    LOGGER.log(Level.WARNING, "Failed to bind to "+ldapServer, e);
                    namingException = e; // retry
                }
            }
            // if all the attempts failed
            LOGGER.log(Level.WARNING, "All attempts to login failed for user {0}", principalName);
            throw namingException;
        }

        /**
         * Binds to the server using the specified username/password.
         * <p>
         * In a real deployment, often there are servers that don't respond or
         * otherwise broken, so try all the servers.
         */
        @Deprecated
        public DirContext bind(String principalName, String password, List<SocketInfo> ldapServers) throws NamingException {
            return bind(principalName, password, ldapServers, new Hashtable<String, String>());
        }

        private void customizeLdapProperty(Hashtable<String, String> props, String propName) {
            String prop = System.getProperty(propName, null);
            if (prop != null) {
                props.put(propName, prop);
            }
        }

        /** Lookups for hardcoded LDAP properties if they are specified as System properties and uses them */
        private void customizeLdapProperties(Hashtable<String, String> props) {
             customizeLdapProperty(props, "com.sun.jndi.ldap.connect.timeout");
             customizeLdapProperty(props, "com.sun.jndi.ldap.read.timeout");
        }

        @SuppressFBWarnings(value = "UPM_UNCALLED_PRIVATE_METHOD", justification = "Deprecated method.It will removed at some point")
        @Deprecated
        private LdapContext bind(String principalName, String password, SocketInfo server, Hashtable<String, String> props) throws NamingException {
            return bind(principalName, password, server, props, null, isRequireTLS());
        }

        @Deprecated
        private LdapContext bind(String principalName, String password, SocketInfo server, Hashtable<String, String> props, TlsConfiguration tlsConfiguration, boolean requireTLS) throws NamingException {
            return bind(principalName, password, server, props, tlsConfiguration, requireTLS, isStartTLS());
        }

        private LdapContext bind(String principalName, String password, SocketInfo server, Hashtable<String, String> props, TlsConfiguration tlsConfiguration, boolean requireTLS, boolean startTLS) throws NamingException {
            String ldapUrl = (requireTLS?"ldaps://":"ldap://") + server + '/';
            String oldName = Thread.currentThread().getName();
            Thread.currentThread().setName("Connecting to "+ldapUrl+" : "+oldName);
            LOGGER.fine("Connecting to " + ldapUrl);
            try {
                props.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
                props.put(Context.PROVIDER_URL, ldapUrl);
                props.put("java.naming.ldap.version", "3");
                customizeLdapProperties(props);

                LdapContext context = new InitialLdapContext(props, null);

                if (!requireTLS && startTLS) {
                    // try to upgrade to TLS if we can, but failing to do so isn't fatal
                    // see http://download.oracle.com/javase/jndi/tutorial/ldap/ext/starttls.html
                    StartTlsResponse rsp = null;
                    try {
                        rsp = (StartTlsResponse)context.extendedOperation(new StartTlsRequest());
                        if (isTrustAllCertificatesEnabled(tlsConfiguration)) {
                            rsp.negotiate((SSLSocketFactory)TrustAllSocketFactory.getDefault());
                        } else {
                            rsp.negotiate();
                        }
                        LOGGER.fine("Connection upgraded to TLS");
                    } catch (NamingException | IOException e) {
                        LOGGER.log(Level.FINE, "Failed to start TLS. Authentication will be done via plain-text LDAP", e);
                        try {
                            if (rsp != null) {
                                rsp.close();
                            }
                        } catch (IOException e1) {
                            LOGGER.log(Level.FINE, "Failed to close StartTLS connection", e1);
                        }
                        // JENKINS-44787 It seems than to go back to plain-text LDAP does not work
                        // in all cases and to re-create the context is necessary
                        context.close();
                        context = new InitialLdapContext(props, null);
                    }
                }

                if (principalName==null || password==null || password.equals("")) {
                    // anonymous bind. LDAP uses empty password as a signal to anonymous bind (RFC 2829 5.1),
                    // which means it can never be the actual user password.
                    context.addToEnvironment(Context.SECURITY_AUTHENTICATION, "none");
                    LOGGER.fine("Binding anonymously to "+ldapUrl);
                } else {
                    // authenticate after upgrading to TLS, so that the credential won't go in clear text
                    context.addToEnvironment(Context.SECURITY_PRINCIPAL, principalName);
                    context.addToEnvironment(Context.SECURITY_CREDENTIALS, password);
                    LOGGER.fine("Binding as "+principalName+" to "+ldapUrl);
                }

                // this is supposed to cause the LDAP bind operation with the server,
                // but I notice that AD may still accept this and yet fail to search later,
                // when I tried anonymous bind.
                // if I do specify a wrong credential, this seems to fail.
                context.reconnect(null);

                return context; // worked
            } finally {
                Thread.currentThread().setName(oldName);
            }
        }

        @Deprecated
        public List<SocketInfo> obtainLDAPServer(String domainName, String site, String preferredServer) throws NamingException {
            return obtainLDAPServer(DNSUtils.createDNSLookupContext(), domainName, site, preferredServer);
        }

        public List<SocketInfo> obtainLDAPServer(ActiveDirectoryDomain activeDirectoryDomain) throws NamingException {
            return obtainLDAPServer(DNSUtils.createDNSLookupContext(), activeDirectoryDomain.getName(), activeDirectoryDomain.getSite(), activeDirectoryDomain.getServers());
        }

        /**
         * @deprecated see obtainLDAPServer(DirContext, String, String, String, boolean)
         */
        @Deprecated
        public List<SocketInfo> obtainLDAPServer(DirContext ictx, String domainName, String site, String preferredServers) throws NamingException {
            return obtainLDAPServer(ictx, domainName, site, preferredServers, isRequireTLS());
        }

        /**
         * Use DNS and obtains the LDAP servers that we should try.
         *
         * @param preferredServers
         *      If non-null, these servers are reported instead of doing the discovery.
         *      In previous versions, this was simply added on top of the auto-discovered list, but this option
         *      is useful when you have many domain controllers (because a single mistyped password can cause
         *      an authentication attempt with every listed server, which can lock the user out!) This also
         *      puts this feature in alignment with {@link #DOMAIN_CONTROLLERS}, which seems to indicate that
         *      there are users who prefer this behaviour.
         *
         * @param useTLS {@code true} if we should use ldaps.
         * @return A list with at least one item.
         */
        public List<SocketInfo> obtainLDAPServer(DirContext ictx, String domainName, String site, String preferredServers, boolean useTLS) throws NamingException {
            List<SocketInfo> result = new ArrayList<>();
            if (preferredServers==null || preferredServers.isEmpty())
                preferredServers = DOMAIN_CONTROLLERS;

            if (preferredServers!=null) {
                for (String token : preferredServers.split(",")) {
                    result.add(new SocketInfo(token.trim()));
                }
                return result;
            }


            String ldapServer = null;
            Attribute a = null;
            NamingException failure = null;

            // try global catalog if it exists first, then the particular domain
            for (ActiveDirectoryDomain.Catalog catalog : ActiveDirectoryDomain.Catalog.values()) {
                ldapServer = catalog + (site!=null ? site + "._sites." : "") + domainName;
                LOGGER.fine("Attempting to resolve " + ldapServer + " to SRV record");
                try {
                    Attributes attributes = ictx.getAttributes(ldapServer, new String[] { "SRV" });
                    a = attributes.get("SRV");
                    if (a!=null)
                        break;
                } catch (NamingException e) {
                    // failed retrieval. try next option.
                    failure = e;
                } catch (NumberFormatException x) {
                    failure = (NamingException) new NamingException("JDK IPv6 bug encountered").initCause(x);
                }
            }

            if (a!=null) {
                // discover servers
                class PrioritizedSocketInfo implements Comparable<PrioritizedSocketInfo> {
                    SocketInfo socket;
                    int priority;

                    PrioritizedSocketInfo(SocketInfo socket, int priority) {
                        this.socket = socket;
                        this.priority = priority;
                    }

                    @Override
                    public int compareTo(PrioritizedSocketInfo that) {
                        return that.priority - this.priority; // sort them so that bigger priority comes first
                    }

                    @Override
                    public boolean equals(Object o) {
                        if (this == o) {
                            return true;
                        }
                        if (!(o instanceof PrioritizedSocketInfo that)) {
                            return false;
                        }
                        return priority == that.priority && Objects.equals(socket, that.socket);
                    }

                    @Override
                    public int hashCode() {
                        return Objects.hash(socket, priority);
                    }
                }
                List<PrioritizedSocketInfo> plist = new ArrayList<>();
                for (NamingEnumeration ne = a.getAll(); ne.hasMoreElements();) {
                    String record = ne.next().toString();
                    LOGGER.fine("SRV record found: "+record);
                    String[] fields = record.split(" ");
                    // fields[1]: weight
                    // fields[2]: port
                    // fields[3]: target host name

                    String hostName = fields[3];
                    // cut off trailing ".". JENKINS-2647
                    if (hostName.endsWith("."))
                        hostName = hostName.substring(0, hostName.length()-1);
                    int port = Integer.parseInt(fields[2]);
                    if (isRequireTLS()) {
                        // map to LDAPS ports. I don't think there's any SRV records specifically for LDAPS.
                        // I think Microsoft considers LDAP+TLS the way to go, or else there should have been
                        // separate SRV entries.
                        if (port==389)  port=636;
                        if (port==3268) port=3269;
                    }
                    int p = Integer.parseInt(fields[0]);
                    plist.add(new PrioritizedSocketInfo(new SocketInfo(hostName, port),p));
                }
                Collections.sort(plist);
                for (PrioritizedSocketInfo psi : plist)
                    result.add(psi.socket);
            }

            if (result.isEmpty()) {
                NamingException x = new NamingException("No SRV record found for " + ldapServer);
                if (failure!=null)  x.initCause(failure);
                throw x;
            }

            LOGGER.fine(ldapServer + " resolved to " + result);
            return result;
        }

        @Deprecated // this is here purely to ease access to the variable from the descriptor.
        private boolean isRequireTLS() {
            boolean requireTLS = true; // secure by default
            SecurityRealm securityRealm = Jenkins.get().getSecurityRealm();
            if (securityRealm instanceof ActiveDirectorySecurityRealm) {
                requireTLS = Boolean.TRUE.equals(((ActiveDirectorySecurityRealm)securityRealm).getRequireTLS());
            }
            return requireTLS;
        }

        @Deprecated // this is here purely to ease access to the variable from the descriptor.
        private boolean isStartTLS() {
            boolean startTLS = true; // secure by default
            SecurityRealm securityRealm = Jenkins.get().getSecurityRealm();
            if (securityRealm instanceof ActiveDirectorySecurityRealm) {
                startTLS = Boolean.TRUE.equals(((ActiveDirectorySecurityRealm)securityRealm).isStartTls());
            }
            return startTLS;
        }
    }

    @Override
    public GroupDetails loadGroupByGroupname2(String groupname, boolean fetchMembers) throws UsernameNotFoundException {
        return getAuthenticationProvider().loadGroupByGroupname(groupname);
    }

    /**
     * Interface that actually talks to Active Directory.
     */
    private synchronized AbstractActiveDirectoryAuthenticationProvider getAuthenticationProvider() {
        if (authenticationProvider == null) {
            authenticationProvider = createAuthenticationProvider();
        }
        return authenticationProvider;
    }

    private AbstractActiveDirectoryAuthenticationProvider createAuthenticationProvider() {
        if (getDomains() == null && getDescriptor().canDoNativeAuth()) {
            // Windows path requires com4j, which is currently only supported on Win32
            return new ActiveDirectoryAuthenticationProvider(this);
        } else {
            return new ActiveDirectoryUnixAuthenticationProvider(this);
        }
    }

    @Override
    public UserDetails loadUserByUsername2(String username) throws UsernameNotFoundException {
        // delegate to one of our ActiveDirectory(Unix)?AuthenticationProvider
        return getAuthenticationProvider().loadUserByUsername(username);
    }

    @Override
    protected UserDetails authenticate2(String username, String password) throws AuthenticationException {
        // Check if the password length is less than 14 characters
        if(FIPS140.useCompliantAlgorithms() && StringUtils.length(password) < 14) {
            LOGGER.log(Level.SEVERE, String.format(Messages.passwordTooShortFIPS()));
            throw new AuthenticationServiceException(Messages.passwordTooShortFIPS());
        }
        UserDetails userDetails = getAuthenticationProvider().retrieveUser(username,new UsernamePasswordAuthenticationToken(username,password));
        SecurityListener.fireAuthenticated2(userDetails);
        return userDetails;
    }

    private static final Logger LOGGER = Logger.getLogger(ActiveDirectorySecurityRealm.class.getName());

    /**
     * If non-null, this value specifies the domain controllers and overrides all the lookups.
     *
     * The format is "host:port,host:port,..."
     *
     * @deprecated as of 1.28
     *      Use the UI field.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Diagnostic fields are left mutable so that groovy console can be used to dynamically turn/off probes.")
    public static String DOMAIN_CONTROLLERS = System.getProperty(ActiveDirectorySecurityRealm.class.getName()+".domainControllers");

    /**
     * Store all the extra environment variable to be used on the LDAP Context
     */
    public static class EnvironmentProperty extends AbstractDescribableImpl<EnvironmentProperty> implements Serializable {
        private final String name;
        private final String value;

        @DataBoundConstructor
        public EnvironmentProperty(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        public static Map<String,String> toMap(List<EnvironmentProperty> properties) {
            final Map<String, String> result = new LinkedHashMap<>();
            if (properties != null) {
                for (EnvironmentProperty property:properties) {
                    result.put(property.getName(), property.getValue());
                }
                return result;
            }
            return result;
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<EnvironmentProperty> {

            @Override
            public String getDisplayName() {
                return "Active Directory";
            }
        }
    }
}
