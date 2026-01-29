# MySQL Transaction Patterns

## Always Use TransactionTemplate

```java
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class UserService {

    private final @NonNull TransactionTemplate transactionTemplate;

    // Read operations
    public UserResponse getUser(String id) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(UserDao.class);
            return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: '%s'".formatted(id)));
        });
    }

    // Write operations
    public UserResponse createUser(UserCreateRequest request) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(UserDao.class);
            var user = buildUser(request);
            return repository.create(user);
        });
    }
}
```

## Transaction Block Best Practices

```java
// ✅ GOOD - Only database operations inside transaction
public UserResponse createUser(UserCreateRequest request) {
    return transactionTemplate.inTransaction(WRITE, handle -> {
        var repository = handle.attach(UserDao.class);
        var user = buildUser(request);
        return repository.create(user);
    });
}

// ❌ BAD - Unrelated logic in transaction block
public UserResponse createUser(UserCreateRequest request) {
    return transactionTemplate.inTransaction(WRITE, handle -> {
        sendEmailNotification(request.getEmail());  // Don't!
        updateCache();  // Don't!

        var repository = handle.attach(UserDao.class);
        return repository.create(buildUser(request));
    });
}
```

## DAO Interface Pattern

```java
@RegisterRowMapper(UserRowMapper.class)
public interface UserDao {

    @SqlQuery("SELECT * FROM users WHERE id = :id")
    Optional<User> findById(@Bind("id") String id);

    @SqlUpdate("INSERT INTO users (id, name, email, created_at) VALUES (:id, :name, :email, :createdAt)")
    @GetGeneratedKeys
    User create(@BindBean User user);
}
```
