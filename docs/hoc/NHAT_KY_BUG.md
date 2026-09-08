# Nhật ký bug — wallet-ledger

Ghi lại **mọi lỗi thật gặp trong lúc xây**, theo đúng công thức:
triệu chứng → giả thuyết đầu tiên (thường sai) → vì sao sai → cách sửa đúng → bài học.

File này được viết dần trong lúc code. Cuối dự án nó thành mục 10 của
`KIEN_TRUC_VA_QUYET_DINH.md`. Ở dự án trước, 9 bug kiểu này là phần giá trị nhất khi phỏng vấn.

---

## 1. Tắt Ryuk thì mất luôn cơ chế dọn container

**Giai đoạn:** Task 1 · **Ai bắt được:** kiểm tra tay sau khi build xanh

**Triệu chứng.** Build thành công, nhưng `docker ps -a --filter "label=org.testcontainers=true"`
cho ra 6 container `postgres:16-alpine` sống 9–14 giờ, từ các phiên làm việc trước.

**Giả thuyết đầu tiên.** "Lần build vừa rồi không dọn dẹp." **Sai.** Tổng số container là 52 cả
trước lẫn sau khi chạy — container của lần này đã tự biến mất.

**Vì sao sai, và sự thật là gì.** Testcontainers có **hai** cơ chế dọn dẹp:

| Cơ chế | Chạy khi nào | Trạng thái ở đây |
|---|---|---|
| Shutdown hook của JVM | JVM thoát **bình thường** | Đang hoạt động |
| **Ryuk** (container canh gác) | JVM bị **giết ngang**, hết giờ, mất điện | **Đã tắt** bằng `TESTCONTAINERS_RYUK_DISABLED=true` |

Sáu container mồ côi là từ những lần JVM bị giết ngang. Ryuk lẽ ra dọn chúng, nhưng nó bị tắt.

**Vì sao vẫn phải tắt Ryuk.** Maven chạy trong container và mount socket của Docker host. Ryuk gắn
nhãn theo phiên rồi tự dọn, nhưng trong mô hình socket lồng nhau này nó hay treo ở bước
"Waiting for container". Tắt là đánh đổi có ý thức, không phải sơ suất.

**Cách sống chung.** Dọn tay khi cần, chỉ nhắm đúng nhãn của Testcontainers:

```
for /f %i in ('docker ps -aq --filter "label=org.testcontainers=true"') do docker rm -f %i
```

**Bài học.** Tắt một cơ chế an toàn thì phải biết **chính xác** nó bảo vệ tình huống nào. Ở đây
nó không bảo vệ đường chạy bình thường — nó bảo vệ đường chạy bất thường, mà đường bất thường
thì đúng là hay xảy ra nhất lúc đang debug.

---

## 2. PowerShell cắt đôi tham số `-Dit.test=...` của Maven

**Giai đoạn:** Task 2 · **Ai bắt được:** chạy lệnh và đọc output

**Triệu chứng.**

```
[ERROR] Unknown lifecycle phase ".test=AuthSchemaIT". You must specify a valid lifecycle phase...
```

**Giả thuyết đầu tiên.** "Sai tên property, chắc failsafe không dùng `it.test`." **Sai** — thông
báo lỗi đã nói hết: Maven nhận được một tham số tên `.test=AuthSchemaIT`, tức là chuỗi
`-Dit.test=AuthSchemaIT` **đã bị tách làm hai** trước khi tới Maven.

**Vì sao.** PowerShell tự phân tích chuỗi tham số không đặt trong ngoặc kép, và cắt ở dấu chấm.
Đây là lỗi của **shell**, không phải của Maven.

**Cách sửa.** Bọc ngoặc kép từng tham số `-D`:

```powershell
mvn -B verify "-Dit.test=AuthSchemaIT" "-DfailIfNoSpecifiedTests=false"
```

**Bài học.** Lỗi báo ở tầng nào chưa chắc là lỗi của tầng đó. `Unknown lifecycle phase` nghe như
lỗi Maven, nhưng nội dung của nó — một mảnh chuỗi bị cắt — chỉ thẳng vào shell. Đọc kỹ **nội
dung** thông báo, đừng chỉ đọc tên exception.

---

## 3. Message của Spring không mang theo chi tiết lỗi của PostgreSQL

**Giai đoạn:** Task 2 · **Ai bắt được:** đọc output của lần chạy đỏ theo TDD

**Triệu chứng.** Test khẳng định constraint bị vi phạm:

```java
assertThatThrownBy(...).hasMessageContaining("ck_app_user_role");
```

Lần chạy đỏ (bảng chưa tồn tại) in ra message thật của exception:

```
StatementCallback; bad SQL grammar [insert into app_user (username, password_hash, role) values (...)]
```

**Điều đáng chú ý.** Message này **không chứa** dòng `relation "app_user" does not exist` của
PostgreSQL. Nghĩa là kể cả khi bảng đã tồn tại và constraint đã chặn đúng, phép so
`hasMessageContaining("ck_app_user_role")` **vẫn sẽ đỏ** — và tôi sẽ tưởng constraint không hoạt
động, rồi đi sửa migration vốn không có lỗi gì.

**Vì sao.** `SQLExceptionTranslator` của Spring dựng message mới từ mô tả tác vụ và câu SQL. Chi
tiết của PostgreSQL nằm ở `PSQLException` bên trong chuỗi `cause`, không được nối vào message ngoài.

**Cách sửa.**

```java
assertThatThrownBy(...)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasStackTraceContaining("ck_app_user_role");
```

`hasStackTraceContaining` soi toàn bộ chuỗi cause nên thấy được message gốc.

**Bài học lớn hơn.** Đây là lợi ích cụ thể của việc **chạy bước đỏ trong TDD**. Nếu viết migration
trước rồi mới chạy test, test sẽ đỏ và tôi sẽ đổ lỗi cho migration. Chính lần chạy đỏ *có chủ ý*
đã in ra message thật và cho thấy phép khẳng định mới là thứ sai. Bước đỏ không chỉ chứng minh
test biết thất bại — nó cho ta xem **hình dạng thật của thất bại**.

---

## 4. Vòng đời container của JUnit đánh nhau với context cache của Spring

**Giai đoạn:** Task 3 · **Ai bắt được:** chạy `mvn verify` đầy đủ với 3 test class

Bug đáng giá nhất tới lúc này. Nó chỉ xuất hiện khi có **từ hai test class trở lên** — nên
Task 1 và Task 2 hoàn toàn xanh và không hé lộ gì.

**Triệu chứng.** `AuthSchemaIT` xanh (3 test). Rồi `LedgerSchemaIT` **8 lỗi** và `ToolchainIT`
**1 lỗi**, tất cả đều là `CannotGetJdbcConnectionException`, mỗi test đúng **30,0 giây**.
`LedgerSchemaIT` mất 244 giây chỉ để chờ hết giờ.

**Giả thuyết đầu tiên.** "Migration `V2` hỏng gì đó, hoặc rò rỉ connection làm cạn pool."
**Sai cả hai.**

**Vì sao sai.** Log nói rõ:

```
Caused by: HikariPool-1 - Connection is not available, request timed out after 30000ms
           (total=0, active=0, idle=0, waiting=0)
Caused by: PSQLException: Connection to host.docker.internal:49677 refused.
```

`total=0` nghĩa là pool **không có connection nào cả** — không phải cạn, mà là **không tạo nổi**.
Nếu là rò rỉ thì `total` phải bằng kích thước tối đa. Và `Successfully applied 2 migrations` đã
in ra trước đó, nên `V2` không sao.

**Bằng chứng quyết định.** Đếm trong báo cáo failsafe:

| Đếm được | Con số |
|---|---|
| Dòng `Creating container for image: postgres:16-alpine` | **3** (`02082f3440f1`, `912b572fc83e`, `f119772a57e5`) |
| Port khác nhau trong các lỗi | **1** — luôn là `49677` |

Ba container được tạo, nhưng chỉ một port bị nhắc tới. Đó là toàn bộ câu chuyện.

**Nguyên nhân gốc.** Hai thành phần quản vòng đời theo **hai nhịp khác nhau**:

| Thành phần | Phạm vi vòng đời |
|---|---|
| `@Testcontainers` + `@Container` trên field static | **Mỗi test class** — extension gọi `stop()` ở `afterAll` |
| Context cache của Spring TestContext | **Cả JVM** — ba class có cấu hình giống hệt nhau nên dùng chung **một** context |

Diễn biến: class 1 khởi động container ở port 49677, Spring tạo context với `DataSource` trỏ
vào đó, test xanh. Hết class 1, extension **giết container**. Class 2 nạp context từ cache —
`DataSource` cũ, vẫn ghim 49677 — trong khi extension đã dựng một container mới ở port khác.
Từ đó mọi truy vấn đâm vào một cái xác.

**Vì sao class đầu luôn xanh.** Đây là điều làm bug khó thấy: nó không phải lỗi ngẫu nhiên, mà
phụ thuộc **thứ tự**. Class chạy đầu tiên luôn đúng. Chạy riêng từng class (`-Dit.test=...`)
cũng luôn đúng. Chỉ vỡ khi chạy cả bộ.

**Cách sửa.** Buộc vòng đời container **khớp với** vòng đời của context — tức là gắn nó vào JVM:

```java
// backend/src/test/java/com/walletledger/AbstractIntegrationTest.java
static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

static {
    POSTGRES.start();
    Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
}
```

Bỏ hẳn `@Testcontainers` và `@Container`. Đây chính là pattern mà `D:\SlangWord` đang dùng —
đọc lại code chạy được của dự án cũ nhanh hơn nhiều so với tự mò.

**Kết quả đo được:**

| | Trước | Sau |
|---|---|---|
| Test | 12 chạy, 9 lỗi | **12 chạy, 0 lỗi** |
| Container tạo ra | 3 | **1** |
| `LedgerSchemaIT` | 244,6 s | **1,419 s** |

**Phần SlangWord còn thiếu.** Nó có `POSTGRES.start()` nhưng **không có shutdown hook**. Cộng
với việc Ryuk bị tắt (bug #1), mỗi lần chạy test lại bỏ lại một container — và đó chính là 6
container `postgres:16-alpine` mồ côi tìm thấy ở Task 1. Thêm một dòng `addShutdownHook` là vá
được cả hai.

**Bài học.** Khi hai framework cùng quản vòng đời của một tài nguyên, hỏng hóc không nằm trong
framework nào cả — nó nằm ở **chỗ hai phạm vi vòng đời không khớp nhau**. Triệu chứng
("connection refused") xuất hiện cách nguyên nhân ("ai gọi `stop()`") rất xa. Cách lần ra là
đếm: *bao nhiêu container được tạo, bao nhiêu port xuất hiện trong lỗi?* Hai con số không khớp
là chỉ thẳng vào nguyên nhân.

---

## 5. `CHAR(64)` làm Hibernate từ chối khởi động

**Giai đoạn:** Task 7 · **Ai bắt được:** bước đỏ của TDD, ngay lúc tạo context

**Triệu chứng.** Toàn bộ context không dựng được:

```
Schema-validation: wrong column type encountered in column [token_hash] in table [refresh_token];
found [bpchar (Types#CHAR)], but expecting [varchar(64) (Types#VARCHAR)]
```

**Nguyên nhân.** `V1__auth.sql` khai báo `token_hash CHAR(64)`. Hibernate ánh xạ `String` sang
`VARCHAR` theo mặc định, và `ddl-auto: validate` so kiểu JDBC chứ không chỉ so tên cột —
`Types.CHAR` (1) khác `Types.VARCHAR` (12).

**Hai cách sửa, và vì sao chọn cách thứ hai.**

| Cách | Việc phải làm | Đánh giá |
|---|---|---|
| Dạy Hibernate chấp nhận | `@JdbcTypeCode(SqlTypes.CHAR)` trên field | Hết lỗi, nhưng **giữ nguyên kiểu dữ liệu sai** |
| Sửa schema | `ALTER COLUMN token_hash TYPE VARCHAR(64)` | Sửa đúng vấn đề |

`CHAR(n)` của PostgreSQL **đệm khoảng trắng** vào cuối và **bỏ qua khoảng trắng cuối khi so
sánh**: `'abc'` và `'abc   '` bằng nhau. Với một phép tra cứu hash chính xác dùng cho bảo mật,
đó là hợp đồng sai — hôm nay chưa cắn vì hash luôn đúng 64 ký tự, nhưng kiểu dữ liệu đang nói
sai về ý định. Chính tài liệu PostgreSQL cũng khuyên không dùng `char(n)`.

Nên lỗi của Hibernate ở đây không phải phiền toái — **nó đang chỉ đúng một khuyết điểm thật**.

**Cách sửa.** `V1` đã commit nên **không được sửa** — đổi file cũ sẽ làm sai checksum của Flyway
ở mọi máy đã chạy nó. Thêm migration mới:

```sql
-- backend/src/main/resources/db/migration/V3__refresh_token_hash_varchar.sql
ALTER TABLE refresh_token ALTER COLUMN token_hash TYPE VARCHAR(64);
```

**Bài học.** `ddl-auto: validate` không phải thủ tục hành chính. Nó là một phép kiểm tra thật,
và ở đây nó bắt được thứ mà không test nào bắt nổi: schema chạy đúng, test SQL thô ở Task 2
cũng xanh, chỉ khi Hibernate soi kiểu mới lộ ra. **Quy tắc:** không bao giờ sửa migration đã
commit — luôn thêm cái mới.

---

## 6. Dụng cụ đo làm hỏng phép đo

**Giai đoạn:** 1B Task 2 · **Ai bắt được:** đối chiếu exit code với báo cáo failsafe

**Triệu chứng.** `mvn verify` in ra `Tests run: 40, Failures: 0, Errors: 0, Skipped: 0` nhưng
tiến trình trả về **exit code 255**, và dòng `BUILD SUCCESS` không hề xuất hiện.

**Giả thuyết đầu tiên.** "Có gì đó hỏng sau khi test chạy xong — plugin verify chăng?" **Sai.**

**Bằng chứng.** `target/failsafe-reports/failsafe-summary.xml`:

```xml
<completed>40</completed><errors>0</errors><failures>0</failures><skipped>0</skipped>
<failureMessage xsi:nil="true"/>
```

Không có lỗi nào. Build thật sự thành công.

**Nguyên nhân gốc.** Lệnh tôi dùng để lọc log:

```powershell
... mvn -B verify 2>&1 | Select-String -Pattern "..." | Select-Object -First 15
```

`Select-Object -First N` **dừng pipeline** ngay khi đủ N phần tử. PowerShell khi đó chấm dứt
tiến trình đứng trước — tức là `docker`. Lần này có hơn 15 dòng khớp nên pipeline bị cắt giữa
chừng, container bị giết, và exit code 255 là của việc bị giết chứ không phải của Maven. Những
lần trước dùng `-First 25` hoặc `-First 30`, số dòng khớp chưa chạm ngưỡng nên không xảy ra.

**Cách sửa.** Đặt ngưỡng đủ lớn, hoặc bỏ hẳn `-First`, hoặc ghi log ra file rồi đọc.

**Bài học.** Bug số 8 của SlangWord là *dấu tích xanh không chứng minh gì*. Đây là mặt còn lại
của cùng một đồng xu: **dấu đỏ cũng có thể không chứng minh gì** — nó có thể là lỗi của dụng cụ
đo. Cách phân biệt là đối chiếu **hai nguồn độc lập**: exit code của tiến trình, và báo cáo mà
chính công cụ ghi ra đĩa. Khi hai nguồn mâu thuẫn, nghi ngờ cái nằm gần mình hơn trước.

---

## 7. Hibernate khoá bằng `FOR NO KEY UPDATE`, không phải `FOR UPDATE`

**Giai đoạn:** 1B Task 3 · **Ai bắt được:** bước bắt buộc "đọc SQL thật" trong plan

**Triệu chứng.** Năm test của `LedgerPostingServiceIT` đều xanh. Nhưng khi bật log SQL rồi tìm
chuỗi `for update` thì **không có dòng nào**.

**Giả thuyết đầu tiên.** "`@Lock(PESSIMISTIC_WRITE)` không được áp — toàn bộ đảm bảo đồng thời
phía dưới là vô nghĩa." **Sai**, và may là đã kiểm trước khi đi sửa.

**Sự thật.** Đổi cách bật log (`-Dspring.jpa.show-sql=true` không có tác dụng; phải dùng
`-Dlogging.level.org.hibernate.SQL=DEBUG`) thì thấy:

```
select a1_0.id,a1_0.balance,a1_0.owner_user_id,a1_0.type,a1_0.version
  from account a1_0 where a1_0.id=? for no key update
```

Khoá **có** được áp. Hibernate 6 trên PostgreSQL ánh xạ `PESSIMISTIC_WRITE` thành
`FOR NO KEY UPDATE` chứ không phải `FOR UPDATE`. Chuỗi `for update` không xuất hiện vì
`for no key update` không chứa nó liền mạch — phép tìm của tôi sai, không phải khoá sai.

**Bốn mức khoá dòng của PostgreSQL, từ yếu tới mạnh:**

| Mức | Xung đột với |
|---|---|
| `FOR KEY SHARE` | `FOR UPDATE` |
| `FOR SHARE` | `FOR NO KEY UPDATE`, `FOR UPDATE` |
| **`FOR NO KEY UPDATE`** | `FOR SHARE`, `FOR NO KEY UPDATE`, `FOR UPDATE` |
| `FOR UPDATE` | tất cả |

Hai giao dịch cùng lấy `FOR NO KEY UPDATE` trên một dòng **vẫn chặn nhau** — đúng thứ ta cần:
loại trừ lẫn nhau giữa hai bên cùng sửa số dư. Khác biệt duy nhất so với `FOR UPDATE` là nó
**không** chặn `FOR KEY SHARE`.

**Và ở đây điều đó lại tốt hơn.** `FOR KEY SHARE` chính là khoá mà PostgreSQL tự lấy trên dòng
`account` khi có ai đó chèn một dòng `ledger_entry` tham chiếu tới nó (kiểm tra khoá ngoại). Nếu
ép dùng `FOR UPDATE`, một giao dịch đang giữ khoá ví A sẽ chặn luôn giao dịch khác chỉ đang ghi
bút toán tham chiếu ví A. `FOR NO KEY UPDATE` tránh được, và vẫn đủ mạnh vì ta không đổi khoá
chính. Đây là mặc định **đúng**, không phải mặc định cần sửa.

**Bài học.** Bước "đọc SQL thật" trong plan đáng giá đúng như kỳ vọng — nhưng nó cũng cho thấy
một cái bẫy thứ hai: **kỳ vọng của chính mình về chuỗi cần tìm cũng có thể sai**. Khi phép tìm
không ra kết quả, hãy hỏi "công cụ có đang chạy không?" trước khi hỏi "code có sai không". Ở đây
`show-sql` im lặng hoàn toàn, và chính sự im lặng tuyệt đối đó — không một dòng SQL nào, kể cả
`insert` — mới là dấu hiệu rằng dụng cụ đo chưa bật, chứ không phải mã hỏng.

---

## 8. Bất biến toàn cục không kiểm chứng được trên một CSDL dùng chung

**Giai đoạn:** 1B Task 4 · **Ai bắt được:** chạy full suite sau khi test riêng đã xanh

**Triệu chứng.** `MoneyServiceIT` chạy **riêng**: 7/7 xanh. Chạy **cả bộ** (`mvn verify`, 52 test):
đúng một test đỏ.

```
MoneyServiceIT.theWholeLedgerStillSumsToZero:126
expected: 0
 but was: 192.5000
```

Dòng 126 là khẳng định thứ hai — `select sum(balance) from account`. Khẳng định thứ nhất,
`select sum(amount) from ledger_entry`, **vẫn xanh**.

**Giả thuyết đầu tiên.** "`MoneyService` làm lệch số dư — nó cộng ở một bên mà quên trừ ở bên kia."
**Sai.** Nếu đúng vậy thì test chạy riêng cũng phải đỏ, và sổ cái cũng phải lệch theo. Ở đây sổ cái
cân bằng tuyệt đối còn số dư thì không — hai con số nói hai chuyện khác nhau, và **chính chỗ lệch
nhau đó là manh mối**, giống hệt bug #4.

**Vì sao sai, và sự thật là gì.** Một container PostgreSQL phục vụ cả JVM (xem bug #4) và **không
có rollback giữa các class test**. Đến lượt `MoneyServiceIT` chạy, tổng số dư đã lệch sẵn — do hai
test khác, **cả hai đều đang làm đúng việc của chúng**:

| Nguồn | Việc nó đang làm | Ảnh hưởng `sum(balance)` |
|---|---|---|
| `AccountPersistenceIT:37` — `wallet.credit(100.0000)` qua entity | Kiểm `@Version` tăng khi số dư đổi. Không đi qua sổ cái, **cố ý** | **+100.0000** |
| `LedgerPostingServiceIT` — 50 + 30 + 12,5 rút từ `SYSTEM_FUNDING` | Đúng nghiệp vụ, cân bằng | 0 (funding = −92,5) |
| `LedgerSchemaIT:64` — `update account set balance = 0 where id = 1` | Kiểm `CHECK` chỉ áp cho ví người dùng; dọn dẹp sau khi đặt −500. **Ghi đè** luôn phần âm ở trên | **+92.5000** |
| | | **Tổng 192.5000** |

Con số tính tay khớp **chính xác** con số trong báo lỗi. Đó là bằng chứng, không phải suy đoán —
và nó tốn ít thời gian hơn chạy lại build 2 phút để in ra bảng số dư.

**Vì sao khẳng định thứ nhất vẫn xanh.** `sum(amount) = 0` được **CSDL** bảo đảm: constraint
trigger `DEFERRABLE INITIALLY DEFERRED` của V2 từ chối mọi giao dịch không cân bằng lúc COMMIT.
Không test nào phá được, kể cả test cố tình phá (`anUnbalancedTransactionIsRejectedAtCommit`).
`sum(balance) = 0` thì **không có gì bảo vệ** — nó chỉ đúng nếu mọi thay đổi số dư đều đi qua
`LedgerPostingService`, và hai test schema thì cố ý đi tắt.

**Cách sửa đúng.** Không đụng vào code sản xuất — nó không sai. Không sửa hai test kia — chúng cũng
không sai. Đổi phép đo: chụp tổng **trước** và **sau**, khẳng định **hiệu bằng 0**.

```java
BigDecimal balancesBefore = totalAccountBalance();
// ... deposit, transfer, withdraw ...
assertThat(totalAccountBalance()).isEqualByComparingTo(balancesBefore);
```

Bất biến thật sự thuộc về `MoneyService` không phải "CSDL sạch" mà là **"một chuỗi thao tác tiền
không tạo ra và không huỷ đi đồng nào"**. Dạng hiệu số nói đúng điều đó, và **mạnh hơn** dạng cũ:
nó đúng bất kể class nào chạy trước để lại gì. Test cũng được đổi tên thành
`moneyIsNeitherCreatedNorDestroyed` cho khớp với điều nó thật sự khẳng định.

Đáng chú ý: pattern đúng **đã có sẵn ngay trong cùng file** — `fundingBefore` và `payoutBefore` ở
hai test phía trên đo bằng hiệu số. Chỉ riêng test cuối vô tình dùng con số tuyệt đối.

**Bài học.** Một khẳng định dạng "tổng toàn hệ thống bằng X" chỉ hợp lệ khi **có thứ gì đó ép nó
đúng**, chứ không phải khi ta tin nó nên đúng. Trước khi viết bất biến toàn cục, hãy hỏi: *ai đang
giữ cho nó đúng?* Nếu câu trả lời là một constraint trong CSDL — cứ viết. Nếu câu trả lời là "vì
code của tôi cẩn thận" — hãy viết dưới dạng **hiệu số**, bởi phép đo tuyệt đối trên trạng thái dùng
chung không đo code của bạn, nó đo cả những người hàng xóm.

Và: **test xanh khi chạy riêng chưa chứng minh gì**. Ở dự án này, chạy riêng một class là cách
nhanh — nhưng cửa duy nhất đáng tin vẫn là `mvn verify` đầy đủ. Đây là bug thứ hai (sau #4) sinh ra
từ đúng một nguyên nhân gốc: **một CSDL, nhiều class test, không rollback**.
