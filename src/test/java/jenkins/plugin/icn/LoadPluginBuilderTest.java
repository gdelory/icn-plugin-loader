package jenkins.plugin.icn;

import java.nio.file.Files;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.params.HttpClientParams;
import org.apache.tools.ant.filters.StringInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import jenkins.plugins.icn.LoadPluginBuilder;

@RunWith(PowerMockRunner.class)
@PrepareForTest(LoadPluginBuilder.class)
@PowerMockIgnore({"javax.crypto.*" })
public class LoadPluginBuilderTest {

    @Rule public JenkinsRule j = new JenkinsRule();
    private FreeStyleProject project;
    private FreeStyleBuild build;
    
    private LoadPluginBuilder buildStep;
    private HttpClient c;
    private PostMethod logonPost;
    private PostMethod loadPost;
    private PostMethod savePost;
    private JSONObject rsp1;
    private JSONObject rsp2;
    private JSONObject rsp3;
    
    @Before
    public void setUp() throws Exception {
        
        // Create a Freestyle job with our build step
        project = j.createFreeStyleProject();
        buildStep = new LoadPluginBuilder("http://nevermind/navigator/", "someadmin", "somepwd", "/some/path");
        project.getBuildersList().add(buildStep);
        
        // Common mock code
        c = Mockito.mock(HttpClient.class);
        logonPost = Mockito.mock(PostMethod.class);
        loadPost = Mockito.mock(PostMethod.class);
        savePost = Mockito.mock(PostMethod.class);
        
        Mockito.when(c.getParams()).thenReturn(Mockito.mock(HttpClientParams.class));
        PowerMockito.whenNew(HttpClient.class).withNoArguments().thenReturn(c);
        PowerMockito.whenNew(PostMethod.class).withArguments(Matchers.anyString()).then(new Answer<PostMethod>() {
          @Override
            public PostMethod answer(InvocationOnMock invocation) throws Throwable {
                String url = invocation.getArgumentAt(0, String.class);
                if (url.contains("logon")) {
                    return logonPost;
                } else if (url.contains("loadPlugin")) {
                    return loadPost;
                } else if (url.contains("configuration")) {
                    return savePost;
                } else {
                    throw new Exception("Unexpected call to url " + url);
                }
            }  
        });
        
        Mockito.when(logonPost.getStatusCode()).thenReturn(200);
        Mockito.when(loadPost.getStatusCode()).thenReturn(200);
        Mockito.when(savePost.getStatusCode()).thenReturn(200);

        rsp1 = new JSONObject();
        rsp1.put("security_token", "567465876");
        
        rsp2 = new JSONObject();
        rsp2.put("name", "plugin-name");
        rsp2.put("id", "plugin-id");
        rsp2.put("version", "plugin-version");
        rsp2.put("configClass", "plugin-config-class");
        
        rsp3 = new JSONObject();
        JSONArray ar = new JSONArray();
        rsp3.put("messages", ar);
        JSONObject mes = new JSONObject();
        ar.put(mes);
        mes.put("text", "This means success");
        Mockito.when(logonPost.getResponseBodyAsStream()).thenReturn(new StringInputStream("{}&&" + rsp1.toString()));
        Mockito.when(loadPost.getResponseBodyAsStream()).thenReturn(new StringInputStream("{}&&" + rsp2.toString()));
        Mockito.when(savePost.getResponseBodyAsStream()).thenReturn(new StringInputStream("{}&&" + rsp3.toString()));
        
    }
    
    /**
     * Check if the logon is correctly applied and used in future requests
     * @throws Exception
     */
    @Test
    public void testLogon() throws Exception {
        
        build = project.scheduleBuild2(0).get();
        
        Mockito.verify(logonPost).addParameter(Matchers.eq(new NameValuePair("userid", "someadmin")));
        Mockito.verify(logonPost).addParameter(Matchers.eq(new NameValuePair("password", "somepwd")));
        Mockito.verify(logonPost).addParameter(Matchers.eq(new NameValuePair("desktop", "admin")));
        Mockito.verify(c).executeMethod(Matchers.eq(logonPost));
        
        // All future requests should embed the security_token
        Mockito.verify(loadPost).addRequestHeader(Matchers.matches("^security_token$"), Matchers.matches("^567465876$"));
        Mockito.verify(savePost).addRequestHeader(Matchers.matches("^security_token$"), Matchers.matches("^567465876$"));
        
        Assert.assertTrue(Files.readAllLines(build.getLogFile().toPath()).contains("Finished: SUCCESS"));
        
    }
    
    /**
     * Check if all three are chained correctly
     * @throws Exception
     */
    @Test
    public void testAll() throws Exception {
        
        build = project.scheduleBuild2(0).get();
        
        Mockito.verify(loadPost).addParameter(Matchers.eq(new NameValuePair("fileName", "/some/path")));
        Mockito.verify(logonPost).addParameter(Matchers.eq(new NameValuePair("desktop", "admin")));
        Mockito.verify(c).executeMethod(Matchers.eq(loadPost));
        
        Mockito.verify(savePost).addParameter(Matchers.eq(new NameValuePair("action", "update")));
        Mockito.verify(savePost).addParameter(Matchers.eq(new NameValuePair("id", "plugin-id")));
        Mockito.verify(savePost).addParameter(Matchers.eq(new NameValuePair("configuration", "PluginConfig")));
        Mockito.verify(c).executeMethod(Matchers.eq(savePost));
        
        Assert.assertTrue(Files.readAllLines(build.getLogFile().toPath()).contains("Finished: SUCCESS"));
        
    }
    
    /**
     * Check if build is failed when login is failing
     * @throws Exception
     */
    @Test
    public void testFailOnLogonFailure() throws Exception {
        
        rsp1.remove("security_token");
        Mockito.when(logonPost.getResponseBodyAsStream()).thenReturn(new StringInputStream("{}&&" + rsp1.toString()));
        build = project.scheduleBuild2(0).get();
        System.out.println(Files.readAllLines(build.getLogFile().toPath()));
        Mockito.verify(c, Mockito.never()).executeMethod(Matchers.eq(loadPost));
        Mockito.verify(c, Mockito.never()).executeMethod(Matchers.eq(savePost));
        Assert.assertTrue(Files.readAllLines(build.getLogFile().toPath()).contains("Finished: FAILURE"));
        
    }

}
