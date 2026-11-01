package cn.ray.agent.server.admin;

import cn.ray.agent.server.identity.OwnerCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.file.*;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class OwnerAndPolicyTest {
    @TempDir Path folder;
    @Test void bootstrapCreatesRandomPasswordAndPersistsOnlyHashInAccountFile() throws Exception {
        var path=folder.resolve("owner.properties");var account=new OwnerCredentials(path.toString());
        var initial=new Properties();try(var reader=Files.newBufferedReader(folder.resolve("initial-login.txt"))){initial.load(reader);}
        String password=initial.getProperty("password");
        assertTrue(password.length()>=32);assertEquals("owner",account.username());
        assertTrue(new BCryptPasswordEncoder().matches(password,account.passwordHash()));
        assertFalse(Files.readString(path).contains(password));
        assertEquals(account.passwordHash(),new OwnerCredentials(path.toString()).passwordHash());
    }
    @Test void policyCannotIntroduceUnknownToolsOrUnboundedBudget() {
        var policy=new RuntimePolicyService.Policy(1,false,true,true,false,2,1,1000,500);
        assertEquals(java.util.Set.of("web_fetch","ask_user"),policy.tools());
        assertThrows(IllegalArgumentException.class,()->new RuntimePolicyService.Policy(1,true,true,true,true,11,6,1000,500));
        assertThrows(IllegalArgumentException.class,()->new RuntimePolicyService.Policy(1,true,true,true,true,2,21,1000,500));
        assertThrows(IllegalArgumentException.class,()->new RuntimePolicyService.Policy(1,true,true,true,true,2,6,600001,500));
    }
}
