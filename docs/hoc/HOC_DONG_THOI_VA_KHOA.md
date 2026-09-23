# Đồng thời và khoá — thứ mà app CRUD không dạy được

> Đây là **lý do dự án này tồn tại**. SlangWord dạy được Spring Boot, JPA, REST, Security, CI.
> Nó **không** dạy được điều gì xảy ra khi hai người cùng rút tiền một lúc, vì một cuốn từ điển
> không có bất biến nào bị phá khi ghi đồng thời.

---

## 1. Bài toán: lost update

Giả sử ví có 100 và hai người cùng rút 100. Code "hiển nhiên":

```java
Account wallet = accounts.findById(id);        // đọc: balance = 100
if (wallet.getBalance() >= amount) {           // kiểm: 100 >= 100 → OK
    wallet.setBalance(wallet.getBalance() - amount);   // ghi: 0
}
```

Hai luồng chạy xen kẽ:

| Thời điểm | Luồng A | Luồng B | balance trong DB |
|---|---|---|---|
| t1 | đọc → 100 | | 100 |
| t2 | | đọc → 100 | 100 |
| t3 | kiểm 100 ≥ 100 ✔ | | 100 |
| t4 | | kiểm 100 ≥ 100 ✔ | 100 |
| t5 | ghi 0 | | 0 |
| t6 | | ghi 0 | **0** |

Hai người rút được **200** từ một ví có **100**. Ngân hàng vừa mất 100.

Đây gọi là **lost update**: bản ghi của A bị B ghi đè, vì B đã đọc trước khi A ghi.

**Điểm cốt tử:** đây không phải bug hiếm. Nó xảy ra bất cứ khi nào có `read → decide → write` mà
không có gì giữ chỗ giữa read và write.

---

## 2. Bốn cách sửa, và vì sao dự án này chọn cách thứ nhất

| Cách | Cơ chế | Đánh đổi |
|---|---|---|
| **Khoá bi quan** (đang dùng) | `SELECT ... FOR UPDATE` — giữ khoá dòng suốt read-modify-write | Đơn giản, luôn đúng. Luồng khác **chờ** |
| Khoá lạc quan | Cột `@Version`, ghi thất bại nếu version đổi | Không chờ, nhưng phải **thử lại**, và người dùng thấy lỗi |
| `SERIALIZABLE` | DB tự phát hiện xung đột | Đúng nhất về lý thuyết, nhưng abort nhiều, phải retry |
| Cập nhật nguyên tử | `UPDATE ... SET balance = balance - ? WHERE balance >= ?` | Nhanh nhất, nhưng không đọc được số dư trước để quyết định logic phức tạp |

Giai đoạn 1C sẽ cài **cả bốn** và chạy **cùng một bộ test** để ra bảng số đo thật — đó là cách duy
nhất biết cái nào nhanh hơn thay vì đoán.

Mốc đã đo được của cách hiện tại (khoá bi quan):

| Bài đo | Thời gian |
|---|---|
| 150 luồng rút 1 từ ví có 100 | **11,97 s** |
| 50 chuyển A→B + 50 chuyển B→A | **13,02 s** |

---

## 3. `SELECT ... FOR UPDATE` làm gì

Nó **khoá dòng** cho tới khi transaction kết thúc. Luồng khác cũng `SELECT ... FOR UPDATE` cùng
dòng đó sẽ **chờ**, không phải đọc giá trị cũ.

Trong [`AccountRepository.java`](../../backend/src/main/java/com/walletledger/account/AccountRepository.java):

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select a from Account a where a.id = :id")
Optional<Account> findByIdForUpdate(@Param("id") Long id);
```

**Vì sao viết `@Query` thay vì để Spring Data tự sinh từ tên method:** một tên method dạng
`findById` giấu mất SQL nó sinh ra. Đây là câu truy vấn duy nhất trong hệ thống mà **hình dạng
chính xác của nó quyết định tiền có mất hay không**. Thứ quan trọng đến vậy thì phải nhìn thấy
được.

### Bẫy thật số 1: Hibernate không phát ra `FOR UPDATE`

Khi bật log SQL và tìm chuỗi `for update`, **không có dòng nào**. Phản ứng đầu tiên: "khoá không
được áp, toàn bộ đảm bảo đồng thời là vô nghĩa".

Sai. SQL thật là:

```sql
select a1_0.id, a1_0.balance, ... from account a1_0 where a1_0.id=? for no key update
```

Hibernate 6 trên PostgreSQL ánh xạ `PESSIMISTIC_WRITE` thành **`FOR NO KEY UPDATE`**.

Bốn mức khoá dòng của PostgreSQL, từ yếu tới mạnh:

| Mức | Xung đột với |
|---|---|
| `FOR KEY SHARE` | `FOR UPDATE` |
| `FOR SHARE` | `FOR NO KEY UPDATE`, `FOR UPDATE` |
| **`FOR NO KEY UPDATE`** | `FOR SHARE`, `FOR NO KEY UPDATE`, `FOR UPDATE` |
| `FOR UPDATE` | tất cả |

Hai giao dịch cùng lấy `FOR NO KEY UPDATE` trên một dòng **vẫn chặn nhau** — đúng thứ ta cần.

**Và ở đây nó còn tốt hơn `FOR UPDATE`.** `FOR KEY SHARE` chính là khoá PostgreSQL tự lấy trên
dòng `account` khi có ai chèn `ledger_entry` tham chiếu tới nó (kiểm tra khoá ngoại). Nếu ép dùng
`FOR UPDATE`, một giao dịch đang giữ ví A sẽ chặn luôn giao dịch khác **chỉ đang ghi bút toán**
tham chiếu ví A. `FOR NO KEY UPDATE` tránh được điều đó, và vẫn đủ mạnh vì ta không đổi khoá chính.

Đây là **mặc định đúng**, không phải mặc định cần sửa. Chi tiết đầy đủ: [bug #7](NHAT_KY_BUG.md).

> **Bài học rộng hơn:** khi phép tìm không ra kết quả, hãy hỏi *"công cụ có đang chạy không?"*
> trước khi hỏi *"code có sai không?"*. Ở đây `-Dspring.jpa.show-sql=true` **không có tác dụng** —
> phải dùng `-Dlogging.level.org.hibernate.SQL=DEBUG`. Sự im lặng tuyệt đối (không một dòng SQL
> nào, kể cả `insert`) mới là dấu hiệu dụng cụ đo chưa bật.

---

## 4. Deadlock và cách chặn nó bằng thứ tự khoá

Deadlock kinh điển: A chuyển cho B **cùng lúc** B chuyển cho A.

```
Giao dịch 1 (A→B):  khoá A ✔  →  xin khoá B  ⟳ chờ
Giao dịch 2 (B→A):  khoá B ✔  →  xin khoá A  ⟳ chờ
```

Mỗi bên giữ thứ bên kia cần. Chờ mãi mãi. PostgreSQL phát hiện sau `deadlock_timeout` (mặc định 1
giây) và **huỷ một giao dịch** với `SQLSTATE 40P01`.

**Cách chặn: luôn khoá theo một thứ tự toàn cục.** Nếu mọi giao dịch đều khoá id nhỏ trước, chu
trình chờ không thể hình thành.

Trong [`LedgerPostingService.java`](../../backend/src/main/java/com/walletledger/ledger/LedgerPostingService.java):

```java
long firstId  = Math.min(fromAccountId, toAccountId);
long secondId = Math.max(fromAccountId, toAccountId);
Account first  = lock(firstId);
Account second = lock(secondId);
```

### Bẫy thật số 2: một câu SQL không đủ

Cách viết ngắn hơn trông rất hấp dẫn:

```sql
SELECT * FROM account WHERE id IN (?, ?) ORDER BY id FOR UPDATE   -- KHÔNG DÙNG
```

**Không được.** PostgreSQL **không cam kết** thứ tự nó lấy khoá dòng *bên trong một câu lệnh*.
`ORDER BY` quy định thứ tự **trả kết quả**, không phải thứ tự **lấy khoá**. Chỉ có hai câu `SELECT`
riêng biệt mới biến thứ tự thành đảm bảo thật.

Đây là loại chi tiết mà đọc tài liệu thì thấy hiển nhiên, còn tự viết thì gần như chắc chắn sai.

### Chứng minh chứ không khẳng định

[`TransferDeadlockIT`](../../backend/src/test/java/com/walletledger/money/TransferDeadlockIT.java)
chạy 50 luồng A→B và 50 luồng B→A đồng thời. Nhưng test xanh **chưa đủ**:

> Test xanh chỉ cho thấy *không có exception nào thoát ra*. Nhìn thấy **sự vắng mặt của SQLSTATE
> `40P01` trong log** mới là khẳng định mạnh hơn.

Nên sau khi test xanh, phải grep log:

```
40P01                 → NONE
CannotAcquireLock     → NONE
deadlock              → có khớp… nhưng MỌI dòng đều là tên class TransferDeadlockIT
```

Dòng cuối là bẫy: grep *có* trả kết quả. Nếu chỉ đếm số khớp mà không đọc từng dòng, sẽ kết luận
ngược hoàn toàn.

---

## 5. Bài test 150 luồng — nó khẳng định gì

[`ConcurrentWithdrawalIT`](../../backend/src/test/java/com/walletledger/money/ConcurrentWithdrawalIT.java):
150 luồng, mỗi luồng rút 1 từ ví có 100.

```java
assertThat(succeeded).isEqualTo(100);              // đúng 100 thành công, 50 bị từ chối
assertThat(balance).isEqualByComparingTo("0.0000"); // không dưới 0, và cũng KHÔNG TRÊN 0
```

Hai chiều đều quan trọng:

- **Dưới 0** → `CHECK` constraint sẽ bắt được.
- **Trên 0** → đó chính là **lost update**: có luồng rút thành công nhưng số dư không giảm.

Và:

```java
if (result.get(120, TimeUnit.SECONDS)) { succeeded++; }
```

Chỉ `InsufficientFundsException` được coi là từ chối hợp lệ. **Bất kỳ exception nào khác** nổi lên
thành `ExecutionException` và làm đỏ test. Nếu bắt hết mọi exception rồi đếm, một lỗi kết nối cũng
sẽ bị đếm là "từ chối" và test sẽ xanh một cách vô nghĩa.

---

## 6. Bẫy thật số 3: connection pool cạn vì một tính năng vô can

Bài test 150 luồng chạy **4 phút 37 giây rồi đỏ** — không phải vì số dư sai:

```
HikariPool-1 - Connection is not available, request timed out after 30001ms
  (total=32, active=32, idle=0, waiting=0)
```

`total=32, active=32, idle=0`. Cả 32 connection bị giữ, không còn cái nào.

**Nguyên nhân.** `AuditLogger` dùng `REQUIRES_NEW` → nó cần **connection thứ hai** *trong khi*
connection thứ nhất vẫn đang bị giữ. Test dùng 32 luồng và pool cũng đúng 32.

**Điểm dễ hiểu nhầm:** chỉ *một* luồng giữ được khoá dòng, 31 luồng kia đang bị chặn ở
`SELECT ... FOR NO KEY UPDATE`. Nhưng **luồng bị chặn vẫn đang giữ connection của nó** — nó chặn
*bên trong* một câu SQL, chứ không phải đang xếp hàng chờ connection.

**Cách sửa:** pool = 64 = 2 × số luồng. Và lý do chọn 64 chứ không phải 33 vừa đủ mới là điều đáng
nhớ:

> Với pool chật, thứ đang cạnh tranh là **hàng đợi connection**. Với pool dư, thứ duy nhất còn
> cạnh tranh là **khoá dòng PostgreSQL** — đúng thứ bài test sinh ra để đo. Pool chật không chỉ làm
> test đỏ, nó làm **phép đo trở nên vô nghĩa**.

**Quy tắc mang đi được:** bất cứ chỗ nào có `REQUIRES_NEW` trên đường nóng, pool phải **ít nhất gấp
đôi** số luồng đồng thời. Và cạn pool kiểu này *không* báo lỗi ngay — nó chờ đủ 30 giây rồi mới
nói, nên trông y hệt một vụ treo.

Chi tiết: [bug #9](NHAT_KY_BUG.md).

---

## 7. Đo cho đúng

Sau khi test xanh, Maven báo:

```
Tests run: 1 ... Time elapsed: 111.4 s -- in ConcurrentWithdrawalIT
```

Suýt ghi 111,4 giây làm số đo. Đọc thẳng `target/failsafe-reports/TEST-*.xml`:

```xml
<testcase name="moreThreadsThanMoneyStillLeavesTheBalanceExact" time="11.974"/>
```

**Gần 100 giây kia là khởi động Spring context**, không phải 150 luồng rút tiền.

| Nguồn số | Giá trị | Dùng được? |
|---|---|---|
| Dòng `Time elapsed` của Maven | 111,4 s | ❌ gồm cả khởi động context |
| `<testcase time=...>` trong XML | **11,974 s** | ✅ |
| Cả class trong full suite (context đã ấm) | 7,0 s | ✅ để so tương đối |

---

## 8. Bốn chiến lược, một bộ test, một bảng số thật (Giai đoạn 1C)

`LedgerPostingService.post` tách phần *khoá* ra sau interface `BalanceMutator` — bốn cách cài,
chạy đúng **cùng một** bộ test (`AbstractConcurrencyContract`), không phải bốn bộ test khác nhau
đo bốn thứ khác nhau.

| Chiến lược | 150 rút cùng lúc | 100 chuyển ngược chiều | Retry (rút / chuyển) | Đúng? |
|---|---|---|---|---|
| **Pessimistic** (đang dùng thật) | 2,158 s | 2,061 s | 0 / 0 | có |
| Optimistic | 6,693 s | 13,629 s | 1858 / 1560 | có |
| Serializable | 5,652 s | 5,125 s | 3035 / 2569 | có |
| Unsafe (không khoá gì) | 0,359 s | 0,319 s | n/a | **không** — chỉ 9/150 thành công |

Đo từ `<testcase time=...>` trong `target/failsafe-reports/TEST-*.xml`, chạy chung một
`mvn verify`, context đã ấm — không phải bốn lần chạy Maven riêng biệt (nhiễu giữa các lần chạy
trên máy này lớn hơn chênh lệch thật giữa các chiến lược).

**Điều bất ngờ nhất: "unsafe" không làm mất tiền.** `Account` mang cột `@Version`
(`docs/hoc/NHAT_KY_BUG.md`, bug #13) — Hibernate kiểm cột này trên **mọi** entity đã quản lý lúc
flush, bất kể chiến lược nào đọc nó ra. `UnsafeBalanceMutator` đọc bằng `accounts.findById()` như
ba chiến lược kia, nên hai giao dịch đụng nhau vẫn bị chặn — chỉ khác là **không ai thử lại**.
Kết quả: tiền không mất (91 = 100 − 9, số dư và sổ cái vẫn khớp tuyệt đối), nhưng thông lượng sụp
— 141/150 lần rút bị từ chối vì tranh chấp version, không phải vì thiếu tiền.

**Optimistic nhanh hơn cả với 1858 lần thử lại — nghịch lý chỉ tưởng vậy.** Số lần thử lại lớn
nhưng mỗi lần thử lại rẻ (một round-trip ngắn, không giữ khoá dòng trong lúc chờ), còn Pessimistic
serial hoá 150 luồng qua đúng một khoá — ít round-trip hơn nhưng mỗi round-trip có luồng phải chờ
thật sự. Trên khối lượng nhỏ (một ví, một cặp ví) kiểu tranh chấp "thử nhanh, thất bại rẻ" thắng
kiểu "chờ tới lượt". Đừng suy ra pessimistic luôn chậm hơn — bài test này không đo tải cao với
nhiều ví khác nhau, nơi hàng nghìn lần thử lại của optimistic mới thật sự tính tiền CPU và
round-trip DB.

**Serializable đắt nhất theo retry (hơn 3000 lần) nhưng không chậm nhất theo thời gian** — SSI của
PostgreSQL phát hiện xung đột sớm và abort rẻ, `40001` xuất hiện thật trong log
(`could not serialize access due to concurrent update`), và `and version=?` cũng được xác nhận
thật trong SQL log của optimistic — không giả định, đúng tinh thần bug #7.

**Vẫn còn:**

- Chưa đo dưới tải kéo dài hoặc với nhiều ví khác nhau đồng thời (bài test này cố tình siết vào
  đúng một điểm tranh chấp để đo rõ sự khác biệt giữa các chiến lược).
- Isolation phenomena (dirty/non-repeatable/phantom read) — mục 9 dưới đây khi được thêm.

---

## 9. Nếu bị hỏi

**"Em xử lý concurrency thế nào?"**
Mọi thao tác tiền đi qua đúng một method. Nó khoá cả hai tài khoản bằng `SELECT ... FOR UPDATE`
theo **thứ tự id tăng dần**, bằng **hai câu SELECT riêng biệt** vì PostgreSQL không cam kết thứ tự
lấy khoá trong một câu lệnh. Chạy ở READ COMMITTED và không phụ thuộc isolation level — tính đúng
đắn đến từ việc giữ khoá dòng suốt read-modify-write. Em có test 150 luồng rút từ ví có 100: đúng
100 thành công, số dư về đúng 0, mất 11,97 giây.

**"Vì sao khoá bi quan mà không lạc quan?"**
Vì trên đường tiền, xung đột là chuyện thường chứ không hiếm — nhiều request cùng chạm một ví là
kịch bản bình thường. Khoá lạc quan phải retry, và retry trên giao dịch tiền là chỗ dễ sinh lỗi
nhất. Giai đoạn 1C em sẽ cài cả bốn chiến lược chạy cùng bộ test để có số đo thật thay vì lý luận.

**"Deadlock thì sao?"**
Chặn bằng thứ tự khoá toàn cục — luôn khoá account id nhỏ trước, nên chu trình chờ không hình
thành được. Test 50 luồng A→B cùng 50 luồng B→A, và em **grep log tìm SQLSTATE 40P01** chứ không
chỉ dựa vào test xanh, vì test xanh chỉ chứng minh không có exception thoát ra.

**"Em có gặp lỗi gì thú vị không?"**
Có, ba cái. Hibernate phát ra `FOR NO KEY UPDATE` chứ không phải `FOR UPDATE` — em tưởng khoá
không hoạt động, hoá ra đó là mặc định đúng và còn tốt hơn. Rồi việc thêm audit log bằng
`REQUIRES_NEW` làm cạn connection pool của chính đường tiền, vì mỗi luồng cần hai connection cùng
lúc. Và một lần suýt ghi nhầm 111 giây làm số đo trong khi thời gian thật là 11,97 — phần còn lại
là khởi động Spring context.
