package jenkins.plugins.icn;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;

import javax.servlet.ServletException;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.cookie.CookiePolicy;
import org.apache.commons.httpclient.methods.PostMethod;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * Implementation of a {@link Builder} step to refresh an ICN plug-in.
 * 
 * @author Guillaume Delory
 * @date Jan 30, 2017
 * 
 *
 */
public class LoadPluginBuilder extends Builder {

    /**
     * ICN desktop to use for the admin, any desktop can be used but hard coded
     * admin will always work and make configuration easier for users than asking them for one 
     */
    private static final String DESKTOP = "admin";
    private static final String SAVE_URL = "jaxrs/admin/configuration";
    private static final String LOAD_URL = "jaxrs/admin/loadPlugin";
    private static final String LOGON_URL = "jaxrs/logon";
    private String url;
    private String file;
    private String username;
    private String password;
    
    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public LoadPluginBuilder(String url, String username, String password, String file) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.file = file;
    }
    
    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public String getFile() {
        return file;
    }
    

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {

        boolean result = false;
        
        HttpClient httpclient = new HttpClient();
        httpclient.getParams().setCookiePolicy(CookiePolicy.BROWSER_COMPATIBILITY);
        PrintStream log = listener.getLogger();
        
        // Variable substitution and safety checks
        EnvVars env = build.getEnvironment(listener);
        file = env.expand(file);
        username = env.expand(username);
        password = env.expand(password);
        url = env.expand(url);
        
        // Apply safety checks on variables
        if (!safetyChecks(log)) {
            return false;
        }
        
        // Logon, reload and save configuration
        String security_token = logon(httpclient, log);
        if (security_token != null) {
            JSONObject loadResult = reload(httpclient, log, security_token);
            if (loadResult != null) {
                try {
                    result = save(httpclient, log, loadResult, security_token);
                } catch (Exception e) {
                    log.println("ERROR: Exception while realoding plugin: " + e.getMessage());
                    e.printStackTrace(log);
                }
            }
        }
        return result;
    }
    
    private boolean safetyChecks(PrintStream log) {
        if (checkEmpty(file, "file", log)) return false;
        if (checkEmpty(username, "udername", log)) return false;
        if (checkEmpty(password, "password", log)) return false;
        if (checkEmpty(url, "url", log)) return false;
        if (!url.endsWith("/")) {
            url += "/";
        }
        return true;
    }

    private boolean checkEmpty(String s, String name, PrintStream log) {
        if (s == null || s.isEmpty()) {
            log.println(name + " can't be empty.");
            return true;
        } else {
            return false;
        }
    }
    
    /**
     * Log on against ICN, this will store the needed cookies and return the 
     * security token header. Both are needed for a successful authentication.
     * @param httpClient the {@link HttpClient} connection to use, this will have
     *                   to be used for all future calls since it gets the
     *                   authentication cookies.
     * @param log the logger as {@link PrintStream}
     * @return the security token, <code>null</code> if anything went wrong.
     *         Exception is already logged if <code>null</code> is returned.
     */
    private String logon(HttpClient httpClient, PrintStream log) {
        log.println("Connecting to ICN as " + username + "...");
        
        String res = null;
        
        PostMethod httpPost = new PostMethod(url + LOGON_URL);
        httpPost.addParameter(new NameValuePair("userid", username));
        httpPost.addParameter(new NameValuePair("password", password));
        httpPost.addParameter(new NameValuePair("desktop", DESKTOP));
        String json = null;
        try {
            httpClient.executeMethod(httpPost);
            System.out.println(httpPost.getStatusLine());
            json = new BufferedReader(new InputStreamReader(httpPost.getResponseBodyAsStream())).readLine();
            // Unsecure the json if prefix is activated in servlet
            if (json.startsWith("{}&&")) {
                json = json.substring(4);
            }
            JSONObject jsonObj = new JSONObject(json);
            
            if (!jsonObj.has("security_token")) {
                log.println("ERROR: Exception while logging into ICN. Response was " + json);
            } else {
                res = (String) jsonObj.get("security_token");
                if (res != null && !"".equals(res)) {
                    log.println("OK");
                } else {
                    log.println("KO");
                }
            }
            
        } catch (Exception e) {
            log.println("KO");
            e.printStackTrace(log.append(e.getMessage()));
            log.println("Login response was: " + json);
        } finally {
            httpPost.releaseConnection();
        }
        return res;
    }
    
    
    /**
     * Reload the plugin from the given path.
     * @param httpClient the {@link HttpClient} to use, it needs to have the authentication
     *                   cookies, brought by a call to the logon method.
     * @param log The {@link PrintStream} to print information to
     * @param security_token the security token to use as header. This is returned by the
     *                       logon method.
     * @return the result of the call, will be needed to save the configuration
     */
    private JSONObject reload(HttpClient httpClient, PrintStream log, String security_token) {
        log.println("Reloading plugin " + file + "...");
        
        JSONObject res = null;
        
        PostMethod httpPost = new PostMethod(url + LOAD_URL);
        httpPost.addParameter(new NameValuePair("fileName", file));
        httpPost.addParameter(new NameValuePair("desktop", DESKTOP));

        httpPost.addRequestHeader("security_token", security_token);
        
        String json = null;
        try {
            httpClient.executeMethod(httpPost);
            if (httpPost.getStatusCode()!= 200) {
                log.println("KO");
                log.println(LOAD_URL + " returned " + httpPost.getStatusLine());
            } else {
                json = new BufferedReader(new InputStreamReader(httpPost.getResponseBodyAsStream())).readLine();
                // Unsecure the json if prefix is activated in servlet
                if (json.startsWith("{}&&")) {
                    json = json.substring(4);
                }
                res = new JSONObject(json);
                
                if (!res.has("name") || !res.has("id") || !res.has("version") || !res.has("configClass")) {
                    log.println("KO");
                    log.println("Response does not have correct attributes: " + json);
                    log.println("It should contain the following attributes: name, id, version, configClass");
                    res = null;
                } else {
                    log.println("OK");
                    log.println("Plug-in " + res.getString("name") + "(id: " + res.getString("id") + ")" + " successfully reloaded.");
                }
                
                
            }
        } catch (Exception e) {
            log.println("KO");
            e.printStackTrace(log.append(e.getMessage()));
            log.println("LoadPlugin response was: " + json);
        } finally {
            httpPost.releaseConnection();
        }
        return res;
    }
    
    
    /**
     * Save the configuration pre-created by the load plugin call.
     * 
     * @param httpClient the {@link HttpClient} to use, it needs to have the authentication
     *                   cookies, brought by a call to the logon method.
     * @param log the {@link PrintStream} to use to print information
     * @param loadResult the resulting {@link JSONObject} from the save operation containing plugin information
     * @param security_token the security token to use as header. This is returned by the
     *                       logon method.
     * @return <code>true</code> if the save is successful
     * @throws JSONException
     */
    private boolean save(HttpClient httpClient, PrintStream log, JSONObject loadResult, String security_token) throws JSONException {
        log.println("Saving configuration...");
        
        boolean res = false;
        
        PostMethod httpPost = new PostMethod(url + SAVE_URL);
        httpPost.addParameter(new NameValuePair("action", "update"));
        httpPost.addParameter(new NameValuePair("id", loadResult.getString("id")));
        httpPost.addParameter(new NameValuePair("configuration", "PluginConfig"));
        httpPost.addParameter(new NameValuePair("desktop", DESKTOP));
        JSONObject json_post = new JSONObject();
        json_post.put("enabled", true);
        json_post.put("filename", file);
        json_post.put("version", loadResult.getString("version"));
        json_post.put("dependencies", new JSONArray());
        json_post.put("name", loadResult.getString("name"));
        json_post.put("id", loadResult.getString("id"));
        json_post.put("configClass", loadResult.getString("configClass"));
        httpPost.addParameter(new NameValuePair("json_post", json_post.toString()));
        
        httpPost.addRequestHeader("security_token", security_token);
        String json = null;
        try {
            httpClient.executeMethod(httpPost);
            if (httpPost.getStatusCode() != 200) {
                log.println("KO");
                log.println(SAVE_URL + " returned " + httpPost.getStatusLine());
            } else {
                json = new BufferedReader(new InputStreamReader(httpPost.getResponseBodyAsStream())).readLine();
                // Unsecure the json if prefix is activated in servlet
                if (json.startsWith("{}&&")) {
                    json = json.substring(4);
                }
                JSONObject jsonObj = new JSONObject(json);
                log.println("JSON conversion OK");
                JSONArray messages = jsonObj.getJSONArray("messages");
                log.println("Message returned is:");
                for (int i = 0; i < messages.length(); i++) {
                    log.println(messages.getJSONObject(i).getString("text"));
                }
                res = true;
            }
        } catch (Exception e) {
            log.println("KO");
            e.printStackTrace(log.append(e.getMessage()));
            log.println("configuration response was: " + json);
        } finally {
            httpPost.releaseConnection();
        }
        return res;
    }
    
    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link LoadPluginBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         */
        public FormValidation doCheckUrl(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a URL");
            if (!value.endsWith("/") && value.startsWith("http"))
                return FormValidation.warning("URL should end with a /");
            return FormValidation.ok();
        }
        
        public FormValidation doCheckUsername(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a Username");
            return FormValidation.ok();
        }
        
        public FormValidation doCheckPassword(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a Password");
            return FormValidation.ok();
        }
        
        public FormValidation doCheckFile(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a File");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Refresh plug-in in ICN";
        }
    }
}

