package dev.atoms.demo.workspace;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class BuildCoordinatorLeaseIT {
    @Autowired DataSource dataSource;
    @Test void onlyOneCoordinatorPerDatabaseEvenWithDifferentFilesystemRoots() throws Exception {
        try(var first=new BuildCoordinatorLease(dataSource)) {
            assertThat(first.healthy()).isTrue();
            assertThatThrownBy(()->new BuildCoordinatorLease(dataSource)).isInstanceOf(java.sql.SQLException.class);
            assertThat(first.healthy()).isTrue();
        }
        try(var next=new BuildCoordinatorLease(dataSource)) { assertThat(next.healthy()).isTrue(); }
    }
}
