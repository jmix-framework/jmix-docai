package com.company.dms.security;

import com.company.dms.model.document.Document;
import com.company.dms.model.user.User;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattr.model.CategoryAttribute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class EmployeeRoleTest {

    private static final String EMPLOYEE = "peter";
    private static final String TEST_USER_PREFIX = "employee-role-test-";

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private AccessManager accessManager;

    @Autowired
    private Metadata metadata;

    @Autowired
    private MetadataTools metadataTools;

    private User colleague;

    @BeforeEach
    void createColleague() {
        User user = dataManager.create(User.class);
        user.setUsername(TEST_USER_PREFIX + System.nanoTime());
        user.setFirstName("Colleague");

        colleague = dataManager.save(user);
    }

    @AfterEach
    void removeTestUsers() {
        dataManager.load(User.class)
                .query("e.username like ?1", TEST_USER_PREFIX + "%")
                .list()
                .forEach(dataManager::remove);
    }

    @Test
    void anEmployeeReadsAColleague() {
        User loaded = systemAuthenticator.withUser(EMPLOYEE,
                () -> dataManager.load(Id.of(colleague)).one());

        assertThat(loaded.getUsername()).isEqualTo(colleague.getUsername());
    }

    @Test
    void anEmployeeCannotChangeAColleague() {
        colleague.setFirstName("Changed");

        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> dataManager.save(colleague)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anEmployeeCannotRemoveAColleague() {
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> dataManager.remove(colleague)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anEmployeeCannotCreateAUser() {
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> {
            User user = dataManager.create(User.class);
            user.setUsername(TEST_USER_PREFIX + "new-" + System.nanoTime());

            dataManager.save(user);
        })).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "firstName", "lastName", "email", "timeZoneId"})
    void anEmployeeSeesTheProfileAttribute(String attribute) {
        EntityAttributeContext context = attributeContext(attribute);

        assertThat(context.canView()).isTrue();
        assertThat(context.canModify()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "active", "passwordChangeRequired"})
    void anEmployeeDoesNotSeeTheAttribute(String attribute) {
        EntityAttributeContext context = attributeContext(attribute);

        assertThat(context.canView()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "documentDate", "category", "+counterparty", "+hireDate"})
    void anEmployeeChangesTheAttributeOfADocument(String attribute) {
        EntityAttributeContext context = new EntityAttributeContext(
                metadataTools.resolveMetaPropertyPath(metadata.getClass(Document.class), attribute));
        systemAuthenticator.runWithUser(EMPLOYEE, () -> accessManager.applyRegisteredConstraints(context));

        assertThat(context.canModify()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(classes = {Category.class, CategoryAttribute.class})
    void anEmployeeReadsTheDocumentTypesButDoesNotChangeThem(Class<?> entityClass) {
        CrudEntityContext context = new CrudEntityContext(metadata.getClass(entityClass));
        systemAuthenticator.runWithUser(EMPLOYEE, () -> accessManager.applyRegisteredConstraints(context));

        assertThat(context.isReadPermitted()).isTrue();
        assertThat(context.isCreatePermitted()).isFalse();
        assertThat(context.isUpdatePermitted()).isFalse();
        assertThat(context.isDeletePermitted()).isFalse();
    }

    private EntityAttributeContext attributeContext(String attribute) {
        EntityAttributeContext context = new EntityAttributeContext(metadata.getClass(User.class), attribute);
        systemAuthenticator.runWithUser(EMPLOYEE, () -> accessManager.applyRegisteredConstraints(context));

        return context;
    }
}
