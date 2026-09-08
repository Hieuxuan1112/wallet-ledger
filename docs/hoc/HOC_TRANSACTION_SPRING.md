# Transaction trong Spring — proxy, propagation, và bốn cái bẫy

> SlangWord có nhắc `@Transactional` ở mức "đặt lên service là xong". Dự án này đẩy nó tới giới hạn:
> transaction lồng nhau, transaction phải **sống sót qua rollback**, và ba lần bị proxy của Spring
> đánh lừa.

---

## 1. `@Transactional` thật ra là gì

Nó **không** phải một tính năng của Java. Spring tạo một **proxy** bọc quanh bean của bạn:

```
Caller  →  Proxy  →  Bean thật
             │
             ├─ mở transaction
             ├─ gọi method thật
             └─ commit (hoặc rollback nếu có RuntimeException)
```

Toàn bộ hành vi transaction nằm ở **proxy**, không nằm trong method.

### Hệ quả số 1: gọi `this.method()` thì proxy bị bỏ qua

```java
@Service
class Foo {
    public void a() {
        this.b();          // ← proxy KHÔNG can thiệp. @Transactional trên b() vô hiệu
    }

    @Transactional
    public void b() { ... }
}
```

Lời gọi `this.b()` đi thẳng vào object thật, không qua proxy. Annotation bị bỏ qua **im lặng** —
không lỗi, không cảnh báo, chỉ là không có transaction.

Đây là lý do `AuditLogger` trong dự án này là **một bean riêng**, không phải một method trong
`MoneyService`.

### Hệ quả số 2: method `private` hoặc `final` cũng vô hiệu

Proxy hoạt động bằng cách kế thừa (CGLIB). Method `private` không override được. Cùng một kiểu thất
bại im lặng.

---

## 2. Propagation — transaction lồng nhau thì sao

| Propagation | Hành vi khi đã có transaction |
|---|---|
| `REQUIRED` (mặc định) | **Tham gia** transaction đang có |
| `REQUIRES_NEW` | **Treo** cái đang có, mở transaction mới trên connection khác |
| `NOT_SUPPORTED` | Treo cái đang có, chạy không transaction |
| `MANDATORY` | Lỗi nếu chưa có transaction |

Trong dự án này chỉ dùng hai cái đầu, nhưng dùng rất có chủ đích.

### `REQUIRED` — vì sao idempotency và bút toán commit cùng nhau

```java
// IdempotentExecutor
@Transactional
public TransactionView claimAndRun(...) {
    records.saveAndFlush(new IdempotencyRecord(...));   // ghi khoá
    TransactionView view = action.get();                // → MoneyService.deposit() (REQUIRED)
    record.complete(...);                               //   → LedgerPostingService.post() (REQUIRED)
    return view;
}
```

`MoneyService.deposit` và `LedgerPostingService.post` đều `@Transactional` mặc định, nên chúng
**tham gia** transaction của `claimAndRun` — **một** transaction duy nhất, **một** lần COMMIT.

Đó chính là lý do khoá idempotency nằm ở PostgreSQL chứ không phải Redis: hai kho lưu trữ **không
có commit chung**, nên hỏng ở giữa thì hoặc trừ tiền hai lần, hoặc mất request.

### `REQUIRES_NEW` — vì sao audit sống sót qua rollback

Một lệnh rút bị từ chối sẽ **rollback**. Nếu dòng audit nằm trong cùng transaction, nó rollback
theo — nghĩa là **chỉ những thao tác thành công mới được ghi log**, đúng chỗ mà log vô dụng nhất.

[`AuditLogger.java`](../../backend/src/main/java/com/walletledger/audit/AuditLogger.java):

```java
@Component
public class AuditLogger {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, String action, String detail, AuditOutcome outcome) {
        jdbc.update("insert into audit_log ...", ...);
    }
}
```

`REQUIRES_NEW` **treo** transaction của caller, mở transaction riêng, commit ngay. Caller rollback
sau đó cũng không đụng tới dòng đã commit.

**Và nó phải là bean riêng.** Nếu viết thành method của `MoneyService` rồi gọi `this.record(...)`,
proxy bị bỏ qua → nó âm thầm tham gia transaction của caller → rollback theo → **đúng cái bug mà
thiết kế này sinh ra để tránh**.

Có test canh giữ điều đó:

```java
// AuditLoggerIT — nếu proxy bị bỏ qua, test này đỏ
assertThatThrownBy(() -> money.withdraw(user.getId(), new BigDecimal("1.0000"), "no funds"))
        .isInstanceOf(InsufficientFundsException.class);

assertThat(auditRows(user.getId(), "FAILURE")).isEqualTo(1);   // dòng audit CÒN LẠI
```

> **Vì sao dùng `JdbcTemplate` chứ không phải entity:** bảng này chỉ ghi, ứng dụng không bao giờ
> đọc lại. Một entity sẽ không mang lại gì và còn tham gia vào persistence context của caller.

---

## 3. Bẫy: `REQUIRES_NEW` tiêu connection thứ hai

`REQUIRES_NEW` treo transaction cũ nhưng **không trả connection về pool** — connection thứ nhất vẫn
bị giữ. Nên tại thời điểm ghi audit, một luồng đang giữ **hai** connection.

Với 32 luồng và pool 32:

```
HikariPool-1 - Connection is not available, request timed out after 30001ms
  (total=32, active=32, idle=0, waiting=0)
```

Cạn pool hoàn toàn. Và nó **không báo lỗi ngay** — chờ đủ 30 giây rồi mới nói, nên trông y hệt treo.

**Quy tắc:** có `REQUIRES_NEW` trên đường nóng ⇒ pool ≥ **2 × số luồng đồng thời**. Chi tiết:
[bug #9](NHAT_KY_BUG.md) và [HOC_DONG_THOI_VA_KHOA.md](HOC_DONG_THOI_VA_KHOA.md) mục 6.

---

## 4. Bẫy: exception trong `catch` nuốt mất exception gốc

Nhìn đoạn này — nó **trông** vô hại:

```java
} catch (RuntimeException e) {
    audit.record(userId, "WITHDRAWAL", "...", AuditOutcome.FAILURE);   // ← nếu dòng này ném thì sao?
    throw e;
}
```

Nếu `audit.record` ném exception, **exception của nó thay thế `e`**. Người dùng nhận `500` thay vì
`409 Insufficient funds`, và nguyên nhân thật biến mất.

Và bug #9 chứng minh kịch bản đó **có thật**: cạn pool khiến chính `audit.record` ném
`CannotGetJdbcConnectionException`.

Cách sửa trong [`MoneyService.java`](../../backend/src/main/java/com/walletledger/money/MoneyService.java):

```java
private void auditFailure(long userId, String action, BigDecimal amount, RuntimeException cause) {
    try {
        audit.record(userId, action, "...", AuditOutcome.FAILURE);
    } catch (RuntimeException auditFailure) {
        cause.addSuppressed(auditFailure);      // giữ cả hai, không thay thế cái nào
    }
}
```

`addSuppressed` là API chuẩn của Java (có từ Java 7, dùng cho try-with-resources): gắn kèm exception
phụ vào exception chính, in ra trong stack trace, mà không đổi cái được ném.

> **Quy tắc:** bất cứ lời gọi nào **bên trong khối `catch`** đều phải được coi là có thể ném. Đó là
> chỗ dễ mất thông tin chẩn đoán nhất trong cả codebase.

---

## 5. Bẫy: `REQUIRES_NEW` commit **trước** khi caller commit

Đây là mặt trái của chính thiết kế ở mục 2.

```java
// TRƯỚC KHI SỬA
LedgerTransaction tx = posting.post(...);
audit.record(userId, "DEPOSIT", "...", AuditOutcome.SUCCESS);   // commit NGAY
return view;                                                     // caller commit SAU
```

Nếu COMMIT của caller **thất bại** — và nó có thể, vì constraint trigger `DEFERRABLE INITIALLY
DEFERRED` chỉ chạy đúng lúc COMMIT — thì sổ cái không có gì, số dư không đổi, nhưng `audit_log`
**vĩnh viễn khẳng định giao dịch đã thành công**.

Audit nói dối, đúng ở tình huống nghiêm trọng nhất.

Bước đỏ của test nói rất gọn:

```
aSuccessRowIsNotWrittenWhenTheSurroundingTransactionRollsBack
expected: 0
 but was: 1
```

### Sửa: hoãn nhánh SUCCESS tới `afterCommit`

```java
private void auditSuccess(long userId, String action, String detail) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            try {
                audit.record(userId, action, detail, AuditOutcome.SUCCESS);
            } catch (RuntimeException e) {
                log.error("Could not audit successful {} for user {}", action, userId, e);
            }
        }
    });
}
```

`TransactionSynchronizationManager.registerSynchronization` đăng ký một callback chạy **sau khi
transaction hiện tại commit xong**. Lúc đó `audit.record` mở transaction mới của nó và ghi.

**Nhánh FAILURE thì giữ nguyên `REQUIRES_NEW`** — ở đó, sống sót qua rollback của caller chính là
mục đích.

| Nhánh | Cơ chế | Vì sao |
|---|---|---|
| SUCCESS | `afterCommit` | Chỉ ghi khi giao dịch **thật sự** xảy ra |
| FAILURE | `REQUIRES_NEW` | Phải sống sót khi caller rollback |

> Trong `afterCommit`, tiền **đã** chuyển rồi. Nên nếu ghi audit hỏng thì **không được** làm request
> lỗi theo — chỉ log thật to. Đó là lý do có `try/catch` bên trong.

---

## 6. Bẫy: vi phạm ràng buộc **huỷ** transaction PostgreSQL

Đây là ràng buộc từ chính PostgreSQL, không phải Spring:

> Sau khi một câu lệnh gây lỗi trong transaction, **mọi câu lệnh tiếp theo đều bị từ chối** cho tới
> khi `ROLLBACK`. PostgreSQL không cho "bỏ qua lỗi rồi đọc tiếp".

Hệ quả trực tiếp lên thiết kế idempotency: kẻ thua trong cuộc đua **không thể** đọc dòng của kẻ
thắng từ bên trong transaction vừa bị lỗi. Nên phải tách hai lớp:

| Class | `@Transactional`? | Việc |
|---|---|---|
| `IdempotencyService` | **Không** | Đọc trước, diễn giải kết quả, quyết định câu trả lời |
| `IdempotentExecutor` | **Có** | Giành khoá + chạy thao tác, cùng commit hoặc cùng rollback |

Chi tiết đầy đủ ở [HOC_IDEMPOTENCY.md](HOC_IDEMPOTENCY.md).

---

## 7. Rollback xảy ra khi nào

| Loại exception | Mặc định |
|---|---|
| `RuntimeException`, `Error` | **Rollback** |
| Checked exception | **KHÔNG** rollback |

Đây là chỗ nhiều người bị bất ngờ. Muốn đổi: `@Transactional(rollbackFor = Exception.class)`.

Dự án này không gặp vấn đề đó vì mọi exception nghiệp vụ đều kế thừa `ErrorResponseException`, mà
nó là `RuntimeException`. Đó là một lợi ích phụ ít ai để ý của việc dùng `ErrorResponseException`
làm lớp cha: **nghiệp vụ thất bại thì transaction tự rollback**, không cần cấu hình gì.

### `setRollbackOnly` — transaction "đã chết nhưng chưa chôn"

Khi một transaction lồng bên trong (`REQUIRED`) ném exception, Spring **đánh dấu** transaction ngoài
là rollback-only. Caller có bắt exception đó cũng không cứu được — tới lúc commit sẽ nhận
`UnexpectedRollbackException`.

Trong `MoneyService`, việc bắt `RuntimeException` để ghi audit **không** gặp vấn đề này, vì
`REQUIRES_NEW` **treo** transaction đang bị đánh dấu và làm việc trên transaction hoàn toàn khác.

---

## 8. `open-in-view: false`

```yaml
spring:
  jpa:
    open-in-view: false
```

Spring Boot mặc định `true`: giữ persistence context mở suốt request, nên entity lazy vẫn load được
ở tầng view. Nghe tiện, nhưng:

- Giấu mất N+1 query — chúng vẫn chạy, chỉ là ở chỗ bạn không nhìn.
- Giữ connection lâu hơn cần thiết.
- Làm ranh giới transaction trở nên mơ hồ.

Trong một dự án mà **ranh giới transaction là nội dung chính**, để `true` sẽ mâu thuẫn với mục tiêu.

---

## 9. Chỗ chưa làm

- **Chưa dùng `@Transactional(readOnly = true)`** cho các đường chỉ đọc. Nó cho phép Hibernate bỏ
  dirty checking và gợi ý cho driver.
- **Chưa có timeout** trên transaction nào. `@Transactional(timeout = 5)` sẽ chặn một transaction
  giữ khoá quá lâu.
- **Chưa thử isolation level khác** READ COMMITTED — thuộc giai đoạn 1C.
- **Chưa có retry** cho các exception tạm thời (`CannotAcquireLockException`). Với khoá bi quan và
  thứ tự khoá cố định thì hiện chưa cần, nhưng bản optimistic ở 1C sẽ cần.

---

## 10. Nếu bị hỏi

**"`@Transactional` hoạt động thế nào?"**
Spring tạo proxy bọc quanh bean; toàn bộ việc mở/commit/rollback nằm ở proxy. Hệ quả quan trọng là
gọi `this.method()` sẽ **bỏ qua proxy** và annotation bị vô hiệu **im lặng**. Trong dự án của em,
`AuditLogger` phải là bean riêng chính vì lý do đó — nếu nó là method trong `MoneyService` thì
`REQUIRES_NEW` sẽ không có tác dụng và dòng audit sẽ rollback cùng thao tác bị từ chối.

**"Khi nào dùng `REQUIRES_NEW`?"**
Khi việc ghi phải sống sót kể cả khi caller rollback — audit log là ví dụ đúng nhất. Nhưng nó có
hai cái giá: nó tiêu **connection thứ hai trong khi cái thứ nhất vẫn bị giữ**, nên pool phải gấp
đôi số luồng; và nó **commit trước caller**, nên dùng cho nhánh thành công sẽ ghi nhầm khi COMMIT
của caller thất bại. Em xử lý bằng cách: nhánh thất bại dùng `REQUIRES_NEW`, nhánh thành công đăng
ký `afterCommit`.

**"Em có gặp bug nào về transaction không?"**
Có ba. Audit `REQUIRES_NEW` làm cạn connection pool khi chạy test 150 luồng. Lời gọi `audit.record`
nằm trong khối `catch` có thể ném và **thay thế** exception nghiệp vụ — em sửa bằng `addSuppressed`.
Và audit ghi SUCCESS trước khi transaction chính commit, nên nó có thể khẳng định một giao dịch
chưa từng xảy ra — test tái hiện bằng cách bọc lời gọi trong một `TransactionTemplate` rồi
`setRollbackOnly`.
