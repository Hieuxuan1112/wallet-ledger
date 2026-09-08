# Sổ cái kép — cách hệ thống tài chính thật lưu tiền

> Nếu chỉ có một cột `balance` trong bảng `user`, bạn không thể trả lời câu hỏi *"tiền này từ đâu
> ra?"*. Sổ cái kép sinh ra để trả lời đúng câu đó — và để **tiền không bao giờ tự nhiên xuất hiện
> hay biến mất**.

---

## 1. Ý tưởng: mỗi đồng đi khỏi đâu đó thì phải đến đâu đó

Nguyên tắc kế toán 500 năm tuổi: mọi giao dịch được ghi thành **ít nhất hai bút toán**, và tổng
của chúng luôn bằng **0**.

Nạp 50 vào ví Alice:

| account | amount |
|---|---|
| 1 — `SYSTEM_FUNDING` | **−50.0000** |
| 7 — ví Alice | **+50.0000** |
| | **tổng = 0** |

Alice chuyển 30 cho Bob:

| account | amount |
|---|---|
| 7 — ví Alice | **−30.0000** |
| 9 — ví Bob | **+30.0000** |
| | **tổng = 0** |

Chạy trên **toàn bộ** cơ sở dữ liệu:

```sql
SELECT SUM(amount) FROM ledger_entry;   -- luôn luôn = 0
```

Nếu câu này ra khác 0, có nghĩa tiền đã được tạo ra hoặc bị huỷ. Đó là lỗi nghiêm trọng nhất mà
một hệ thống tài chính có thể mắc — nên nó được **cơ sở dữ liệu chặn**, không phải code chặn.

## 2. Tài khoản hệ thống — tiền vào ra thế giới bên ngoài

Nếu mọi giao dịch phải cân bằng, thì lúc nạp tiền, phía "âm" là ai?

Câu trả lời: hai tài khoản đại diện cho **thế giới bên ngoài**, seed sẵn trong
[`V2__ledger.sql`](../../backend/src/main/resources/db/migration/V2__ledger.sql):

| id | type | Vai trò |
|---|---|---|
| 1 | `SYSTEM_FUNDING` | Tiền **vào** hệ thống. Càng nạp nhiều, nó càng âm |
| 2 | `SYSTEM_PAYOUT` | Tiền **ra** khỏi hệ thống. Càng rút nhiều, nó càng dương |

Hệ quả đẹp: `ABS(balance)` của `SYSTEM_FUNDING` chính là **tổng số tiền từng được nạp vào hệ
thống**, không cần cộng thêm gì.

Hai tài khoản này **được phép âm**, ví người dùng thì không:

```sql
CONSTRAINT ck_wallet_non_negative CHECK (type <> 'USER_WALLET' OR balance >= 0)
```

Đọc là: *"hoặc đây không phải ví người dùng, hoặc số dư ≥ 0"*.

### Vì sao id cố định 1 và 2

Trong [`MoneyService.java`](../../backend/src/main/java/com/walletledger/money/MoneyService.java):

```java
private static final long SYSTEM_FUNDING = 1L;
private static final long SYSTEM_PAYOUT  = 2L;
```

Vì sao không `accounts.findByType(SYSTEM_FUNDING)`? Vì đó là một câu query **quét bảng `account`
lớn dần theo từng người dùng đăng ký**, để biết một điều mà **migration đã đảm bảo sẵn**. Migration
seed đúng id đó, và `LedgerSchemaIT` có test giữ cho hằng số này không nói dối:

```java
assertThat(jdbc.queryForObject("select type from account where id = 1", String.class))
        .isEqualTo("SYSTEM_FUNDING");
```

Đây là ví dụ của một đánh đổi có ý thức: **hằng số + test canh giữ** thay vì **query mỗi request**.

---

## 3. Vì sao `amount` có dấu, không dùng hai cột debit/credit

Sách kế toán thường dạy hai cột: `debit` và `credit`. Dự án này dùng **một cột có dấu**.

| | Hai cột `debit`/`credit` | Một cột `amount` có dấu |
|---|---|---|
| Kiểm tra cân bằng | `SUM(debit) = SUM(credit)` — hai phép cộng, hai cột | `SUM(amount) = 0` — **một câu SQL** |
| Nguy cơ | Ghi nhầm vào cột sai vẫn "hợp lệ" về kiểu dữ liệu | Dấu sai thì tổng lệch ngay |
| Hiển thị cho người dùng | Có sẵn | Suy ra từ dấu: `amount < 0` → `DEBIT` |

Cái quyết định là dòng đầu: bất biến gói gọn trong **một biểu thức**, nên viết được thành một
constraint trong DB. Với hai cột thì constraint phức tạp hơn hẳn.

API vẫn hiển thị `DEBIT`/`CREDIT` — chỉ là suy ra từ dấu khi trả về, không lưu.

---

## 4. Ba lớp bảo vệ ở tầng cơ sở dữ liệu

Code có thể có bug. Người khác có thể chạy SQL tay. Migration tương lai có thể sai. Ba thứ này vẫn
đúng:

### Lớp 1 — ví không bao giờ âm

```sql
CONSTRAINT ck_wallet_non_negative CHECK (type <> 'USER_WALLET' OR balance >= 0)
```

### Lớp 2 — sổ cái chỉ được ghi thêm, không sửa không xoá

```sql
CREATE OR REPLACE FUNCTION reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entry is append-only; % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entry_immutable
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
```

Sai sót được sửa bằng **giao dịch đảo chiều** (`REVERSAL`), không bao giờ bằng cách sửa bút toán
cũ. Đó là cách kế toán thật hoạt động: sổ cái là lịch sử, và lịch sử không được viết lại.

### Lớp 3 — mọi giao dịch phải cân bằng, kiểm lúc COMMIT

```sql
CREATE CONSTRAINT TRIGGER trg_transaction_balanced
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();
```

**`DEFERRABLE INITIALLY DEFERRED` là mấu chốt.** Một constraint thường chạy ngay sau mỗi dòng — mà
bút toán đầu tiên của mỗi cặp **luôn** lệch (chỉ mới có −50, chưa có +50). Nó sẽ từ chối **mọi**
giao dịch.

Hoãn tới lúc `COMMIT` thì cả hai vế đã tồn tại, và phép kiểm mới có nghĩa.

Có test chứng minh nó thật sự chặn:

```java
assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
        jdbc.update("insert into ledger_entry (transaction_id, account_id, amount) values (?, ?, 10)", tx, wallet)))
        .hasStackTraceContaining("Unbalanced transaction");
```

---

## 5. Cache số dư — và cách đối chiếu

Bảng `account` có cột `balance`. Về lý thuyết nó **thừa**: số dư luôn tính được bằng
`SUM(amount) FROM ledger_entry WHERE account_id = ?`.

Vậy sao vẫn giữ? Vì đọc số dư là thao tác phổ biến nhất, và cộng dồn toàn bộ lịch sử giao dịch mỗi
lần mở app là không chấp nhận được khi lịch sử dài ra.

Đây là **denormalization có chủ đích**, và cái giá của nó là: cache có thể lệch. Nên phải có cách
đối chiếu, và [`LedgerPostingServiceIT`](../../backend/src/test/java/com/walletledger/ledger/LedgerPostingServiceIT.java)
kiểm đúng điều đó:

```java
BigDecimal cached  = accounts.findById(wallet.getId()).orElseThrow().getBalance();
BigDecimal derived = jdbc.queryForObject(
        "select coalesce(sum(amount), 0) from ledger_entry where account_id = ?", ...);

assertThat(cached).isEqualByComparingTo(derived);
```

> **Quy tắc:** mỗi khi bạn cache một giá trị dẫn xuất, bạn nợ hệ thống một cách kiểm tra cache đó
> có đúng không. Không trả nợ đó thì sớm muộn sẽ có ngày không ai biết con số nào đúng.

---

## 6. `NUMERIC(19,4)` — không bao giờ dùng số thực cho tiền

```sql
balance NUMERIC(19,4) NOT NULL DEFAULT 0
```

Vì sao không `DOUBLE`/`FLOAT`:

```
0.1 + 0.2 = 0.30000000000000004     ← trong IEEE-754
```

Với tiền, sai số đó tích luỹ và **không thể chấp nhận**. `NUMERIC` là số thập phân chính xác, không
có sai số làm tròn nhị phân.

Bên Java tương ứng là `BigDecimal`, **không bao giờ** `double`.

### Và ở tầng API

Trong [`TransactionView.java`](../../backend/src/main/java/com/walletledger/money/TransactionView.java):

```java
@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount
```

Số tiền được trả về dưới dạng **chuỗi JSON**, không phải số. Vì **JSON number được nhiều client đọc
thành IEEE-754 double** — `Number` của JavaScript là một ví dụ — và `NUMERIC(19,4)` chứa nhiều chữ
số có nghĩa hơn double giữ được chính xác. Gửi tiền dưới dạng number nghĩa là client React sẽ **âm
thầm làm tròn** những số dư lớn.

### Chặn ở tầng nhập liệu

Trong [`AmountRequest.java`](../../backend/src/main/java/com/walletledger/money/AmountRequest.java):

```java
@DecimalMin(value = "0.0001", message = "amount must be positive")
@Digits(integer = 15, fraction = 4, message = "amount supports at most 4 decimal places")
BigDecimal amount
```

`@Digits(integer = 15, fraction = 4)` phản chiếu **chính xác** `NUMERIC(19,4)`: số lẻ hơn 4 chữ số
thập phân bị **từ chối**, không phải làm tròn im lặng. `@DecimalMin` khiến "rút số âm" không thể
trở thành "nạp tiền trá hình".

---

## 7. Bài học đắt nhất: validation ở biên không thay được validation ở chốt chặn

`LedgerPostingService` được thiết kế làm **chốt chặn** — nơi duy nhất tiền dịch chuyển. Nhưng ban
đầu nó chỉ kiểm `fromAccountId == toAccountId`, **không kiểm dấu của `amount`**:

```java
post(TRANSFER, caller, "x", víCủaCaller, víCủaNạnNhân, new BigDecimal("-50"))
  → from.debit(-50)   → ví caller  TĂNG 50
  → to.credit(-50)    → ví nạn nhân GIẢM 50
```

`ck_wallet_non_negative` chỉ chặn khi nạn nhân xuống dưới 0 — nạn nhân còn tiền thì lệnh **commit
thành công**. Kẻ gọi vừa rút tiền từ ví người khác.

**Vì sao 68 test không bắt được:** `@DecimalMin` trên DTO chặn ở tầng HTTP, nên **mọi test đi qua
endpoint đều xanh**. Nhưng `MoneyService` và `LedgerPostingService` đều public và gọi thẳng được —
và giai đoạn 4 (lớp AI) đã được thiết kế để gọi thẳng tầng service.

Sửa là một dòng. Cái khó là **nhìn ra**:

```java
if (amount.signum() <= 0) {
    throw new IllegalArgumentException("Amount must be positive");
}
```

> **Quy tắc mang đi được:** một hàm tự gọi mình là "chốt chặn" thì phải **tự kiểm đầu vào của
> nó**, vì lời hứa *"mọi thứ đều đi qua đây"* chỉ đúng khi *ở đây* không tin ai cả.

Chi tiết: [bug #10](NHAT_KY_BUG.md).

---

## 8. Những gì cố tình KHÔNG làm

| Không có | Vì sao |
|---|---|
| Cột `currency` | Dự án một loại tiền tệ. Thêm cột chưa dùng là nợ kỹ thuật, không phải chuẩn bị |
| Trạng thái `PENDING` | Chưa có luồng nghiệp vụ nào cần giao dịch treo |
| `@ManyToOne` giữa `LedgerEntry` và `Account` | Sổ cái ghi một lần, đọc dạng danh sách. Không có điều hướng object nào để biện minh cho lazy proxy, và id thuần giữ việc tạo entity **rẻ và an toàn bên trong vùng đang giữ khoá** |

Đây là YAGNI có chủ đích, **không phải quên**.

---

## 9. Chỗ chưa làm

- **Chưa có sao kê** (`GET /transactions`) — giai đoạn 2.
- **Chưa có hoàn tiền.** Cột `reverses_transaction_id` và `UNIQUE` trên nó **đã có sẵn trong
  schema**, `TransactionType.REVERSAL` đã có, nhưng chưa có code nào tạo ra nó.
- **Chưa có job đối chiếu định kỳ** giữa `account.balance` và `SUM(ledger_entry.amount)`. Hiện chỉ
  có test kiểm.
- **Bút toán chỉ luôn là 2 dòng.** Sổ cái kép thật cho phép nhiều hơn (ví dụ: chuyển tiền có phí =
  3 bút toán). Thiết kế hiện tại không cấm, nhưng `post()` chỉ viết đúng 2.

---

## 10. Nếu bị hỏi

**"Vì sao dùng sổ cái kép mà không chỉ một cột balance?"**
Vì một cột balance không trả lời được *"tiền này từ đâu"*, và không có cách nào phát hiện khi nó
sai. Với sổ cái kép, `SELECT SUM(amount) FROM ledger_entry` phải bằng 0 trên toàn bộ CSDL — và em
để **constraint trigger DEFERRABLE** của PostgreSQL ép điều đó lúc COMMIT, chứ không tin vào code.
Em vẫn cache cột `balance` để đọc nhanh, kèm test đối chiếu nó với tổng bút toán.

**"Vì sao constraint phải DEFERRABLE?"**
Vì một constraint thường chạy ngay sau mỗi dòng, mà bút toán đầu tiên của mỗi cặp luôn lệch — chỉ
mới có −50, chưa có +50. Nó sẽ từ chối mọi giao dịch. Hoãn tới COMMIT thì cả hai vế đã tồn tại.

**"Tiền lưu kiểu dữ liệu gì?"**
`NUMERIC(19,4)` trong PostgreSQL, `BigDecimal` trong Java, và trả về API dưới dạng **chuỗi JSON**
chứ không phải number — vì JSON number bị nhiều client đọc thành double và làm tròn mất chữ số.
Validation ở DTO dùng `@Digits(integer = 15, fraction = 4)` khớp chính xác với kiểu DB, nên số lẻ
hơn 4 chữ số bị từ chối chứ không bị làm tròn ngầm.

**"Nếu code có bug thì sao?"**
Đó là lý do có ba lớp bảo vệ nằm trong DB: CHECK ví không âm, trigger sổ cái append-only, và
constraint trigger kiểm cân bằng lúc COMMIT. Chúng đúng kể cả với SQL chạy tay không qua ứng dụng.
