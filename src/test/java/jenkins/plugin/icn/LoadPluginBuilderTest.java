package jenkins.plugin.icn;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import jenkins.plugins.icn.LoadPluginBuilder;

public class LoadPluginBuilderTest {

    @Rule public JenkinsRule j = new JenkinsRule();
    private FreeStyleProject project;
    private FreeStyleBuild build;
    
    @Before
    public void setUp() throws IOException, InterruptedException, ExecutionException {
        project = j.createFreeStyleProject();
        project.getBuildersList().add(new LoadPluginBuilder("http://nevermind/navigator/", "someadmin", "somepwd", "/some/path"));  
        build = project.scheduleBuild2(0).get();
    }
    
    @Test
    public void testLogon() {
        fail("Not yet implemented");
    }

}
