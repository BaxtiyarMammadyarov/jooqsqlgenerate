package az.mbm.jooqsqlgenerate;

import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * {@link JooqManager} nümunələrini yaradan Spring bean.
 *
 * <p>{@code JooqManager} özü artıq Spring bean deyil — hər sorğu üçün
 * bu factory vasitəsilə <b>yeni nümunə</b> yaradılır. Beləliklə columns,
 * filters, joins kimi daxili vəziyyət heç vaxt başqa sorğularla qarışmır.
 *
 * <pre>{@code
 * @Service
 * @RequiredArgsConstructor
 * public class UserService {
 *
 *     private final JooqManagerFactory jooqFactory;
 *
 *     public SelectFetchResponse<UserDto> search(String status, int page, int size) {
 *         return jooqFactory.create()
 *             .setMainTable(User.class, "u")
 *             .addColumns("u.id", "u.name", "u.email")
 *             .addFilter("status", Op.EQUAl, status)
 *             .setPage(page, size)
 *             .fetchInto(UserDto.class);
 *     }
 * }
 * }</pre>
 */
@Component
public class JooqManagerFactory {

    private final DSLContext dsl;

    public JooqManagerFactory(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Təmiz vəziyyətdə yeni {@link JooqManager} nümunəsi yaradır.
     *
     * @return hər dəfə yeni, müstəqil nümunə
     */
    public JooqManager create() {
        return new JooqManager(dsl);
    }
}
