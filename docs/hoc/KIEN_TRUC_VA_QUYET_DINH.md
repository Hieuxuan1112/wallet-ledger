# Kiến trúc và quyết định

> Tài liệu này trả lời câu *"vì sao làm thế này"* cho từng lựa chọn trong repo. Mỗi mục đều có cả
> **phương án đã loại**, vì một quyết định không nêu được cái mình từ chối thì chưa phải quyết định.

---

## 1. Dự án này giải bài toán gì

Một ví điện tử: đăng ký, đăng nhập, xem số dư, nạp, rút, chuyển tiền.

Nhưng đó là **vỏ**. Mục tiêu thật là học ba thứ mà một app CRUD không dạy được:

| | App CRUD (SlangWord) | Dự án này |
|---|---|---|
| Ghi đồng thời | Hai người sửa cùng một từ → ai ghi sau thắng, không ai mất gì | Hai người rút cùng một ví → **mất tiền thật** |
| Bất biến | Không có bất biến nào bị phá | `SUM(amount) = 0` phải đúng mọi lúc |
| Retry | Gọi lại API tạo từ → lỗi 409, vô hại | Gọi lại API nạp tiền → **trừ hai lần** |

Chọn đề tài ví điện tử **chính là để có ba cột bên phải**.

---

## 2. Bố cục package

```
auth/          đăng ký, đăng nhập, JWT, refresh token xoay vòng + phát hiện tái sử dụng
account/       entity ví và model đọc
ledger/        LedgerPostingService — nơi DUY NHẤT số dư thay đổi
money/         nạp / rút / chuyển; ba endpoint HTTP
idempotency/   dòng khoá, replay, xử lý xung đột
audit/         logger REQUIRES_NEW
security/      JWT filter, entry point trả RFC 7807
config/        @ConfigurationProperties
```

**Chia theo tính năng, không chia theo tầng.** Tức là `money/` chứa controller, service và DTO của
nó, thay vì có `controller/`, `service/`, `dto/` ở cấp cao nhất.

| | Chia theo tầng | Chia theo tính năng (đang dùng) |
|---|---|---|
| Sửa một tính năng | Mở 3–4 thư mục | Mở **1** thư mục |
| Nhìn ra chỗ ghép nối sai | Khó — mọi thứ trộn lẫn | Dễ — `import` giữa package là tín hiệu |
| Quen thuộc với người mới | Cao | Trung bình |

Lý do quyết định: giai đoạn 1C sẽ có **luật ArchUnit** cấm một số phụ thuộc. Luật đó chỉ viết được
gọn khi ranh giới là **tính năng**.

---

## 3. Chốt chặn — quyết định kiến trúc quan trọng nhất

**Mọi** thay đổi số dư đi qua đúng một method:

```java
// LedgerPostingService
@Transactional
public LedgerTransaction post(TransactionType type, long initiatedByUserId, String description,
                              long fromAccountId, long toAccountId, BigDecimal amount)
```

Nó làm bốn việc, không hơn: khoá hai tài khoản theo thứ tự id tăng dần, đổi hai số dư, ghi hai bút
toán, trả về transaction.

**Vì sao quan trọng:** khi chỉ có một chỗ tiền dịch chuyển, thì chỉ cần **một** chỗ đúng. Kiểm
tra bảo mật, thêm log, thêm ràng buộc — tất cả đều có đúng một điểm để đặt vào.

**Và chốt chặn phải tự bảo vệ.** Bài học đắt nhất của giai đoạn 1B: `post()` ban đầu không kiểm dấu
`amount`, nên số âm đảo chiều dòng tiền và rút được ví người khác. Validation ở DTO làm **mọi test
HTTP đều xanh** và tạo cảm giác an toàn giả — nhưng tầng service gọi thẳng được, và giai đoạn 4
(lớp AI) sẽ gọi thẳng. Xem [bug #10](NHAT_KY_BUG.md).

> Lời hứa *"mọi thứ đều đi qua đây"* chỉ đúng khi *ở đây* không tin ai cả.

---

## 4. Bảng quyết định

### Sổ cái

| Quyết định | Đã loại | Vì sao |
|---|---|---|
| Bút toán `amount` **có dấu** | Hai cột `debit`/`credit` | Bất biến gọn thành **một** biểu thức `SUM = 0` ⇒ viết được thành constraint DB |
| **Cache** cột `balance` | Tính lại từ sổ cái mỗi lần đọc | Đọc số dư là thao tác phổ biến nhất; cộng dồn cả lịch sử là không chấp nhận được. Trả giá bằng **test đối chiếu** |
| Tài khoản hệ thống id cố định **1, 2** | `findByType()` mỗi request | Query quét bảng lớn dần theo số người dùng, để biết điều migration đã đảm bảo. Hằng số + test canh giữ |
| **Không** có cột `currency` | Thêm sẵn cho tương lai | YAGNI. Cột chưa dùng là nợ, không phải chuẩn bị |
| **Không** có trạng thái `PENDING` | | Chưa có luồng nghiệp vụ nào cần |
| `LedgerEntry` dùng **id thuần**, không `@ManyToOne` | Quan hệ JPA đầy đủ | Sổ cái ghi một lần, đọc dạng danh sách. Không có điều hướng object để biện minh cho lazy proxy, và id thuần giữ việc tạo entity **rẻ và an toàn bên trong vùng đang giữ khoá** |

Chi tiết: [HOC_SO_CAI_KE_TOAN.md](HOC_SO_CAI_KE_TOAN.md).

### Đồng thời

| Quyết định | Đã loại | Vì sao |
|---|---|---|
| **Khoá bi quan** `SELECT ... FOR UPDATE` | Khoá lạc quan (`@Version`) | Trên đường tiền, xung đột là chuyện thường. Optimistic phải retry, và retry trên giao dịch tiền là chỗ dễ sinh lỗi nhất |
| READ COMMITTED | `SERIALIZABLE` | Tính đúng đắn đến từ **giữ khoá dòng**, không từ isolation level. `SERIALIZABLE` abort nhiều, buộc phải retry |
| Khoá theo **thứ tự id tăng dần** | Khoá theo thứ tự nghiệp vụ (from → to) | Thứ tự toàn cục làm chu trình chờ **không thể hình thành** ⇒ không deadlock |
| **Hai câu SELECT riêng biệt** | `WHERE id IN (a,b) ORDER BY id FOR UPDATE` | PostgreSQL **không cam kết** thứ tự lấy khoá bên trong một câu lệnh. `ORDER BY` quy định thứ tự trả kết quả, không phải thứ tự khoá |
| Giữ `FOR NO KEY UPDATE` của Hibernate | Ép dùng `FOR UPDATE` | Nó **tốt hơn**: không chặn `FOR KEY SHARE` mà PostgreSQL tự lấy khi chèn `ledger_entry` tham chiếu tài khoản đó |
| Cột `@Version` **có, nhưng chưa dùng** | Bỏ hẳn | Giai đoạn 1C cần nó để cài bản optimistic và so sánh có số đo |

Chi tiết: [HOC_DONG_THOI_VA_KHOA.md](HOC_DONG_THOI_VA_KHOA.md).

### Idempotency

| Quyết định | Đã loại | Vì sao |
|---|---|---|
| Khoá ở **PostgreSQL** | **Redis** | Khoá và bút toán phải commit nguyên tử. Hai kho không có commit chung ⇒ hoặc trừ hai lần, hoặc nuốt mất request |
| `UNIQUE (user_id, idem_key)` | `UNIQUE (idem_key)` | Phạm vi theo người dùng: hai client trùng chuỗi key không chặn nhau |
| Kẻ thua đồng thời nhận **409** | Chờ rồi trả kết quả kẻ thắng | Response của kẻ thắng **chưa tồn tại**. Đoán nó là bịa |
| Thao tác thất bại **không tiêu key** | Ghi nhận cả thất bại | Client gặp lỗi mạng rồi retry sẽ không bao giờ thành công được nữa |
| Tách **hai bean** | Một service `@Transactional` | Vi phạm unique **huỷ transaction PostgreSQL** ⇒ kẻ thua không thể đọc dòng kẻ thắng từ bên trong |

Chi tiết: [HOC_IDEMPOTENCY.md](HOC_IDEMPOTENCY.md).

### Xác thực

| Quyết định | Vì sao |
|---|---|
| Access token 15 phút + refresh token **xoay vòng** | Token ngắn hạn giảm thiệt hại khi lộ |
| **Phát hiện tái sử dụng** refresh token | Dùng lại token cũ = dấu hiệu bị đánh cắp ⇒ thu hồi **cả họ** token |
| Thu hồi bằng bean riêng `REQUIRES_NEW` | Việc thu hồi phải commit kể cả khi request bị từ chối |
| Ví lấy từ **token**, không từ tham số | `GET /api/v1/wallet` không nhận id nào ⇒ **không có tham số nào để kẻ tấn công đổi** |

### Xử lý lỗi

| Quyết định | Vì sao |
|---|---|
| **RFC 7807** `ProblemDetail` trên mọi đường | Một định dạng lỗi duy nhất, kể cả lỗi validation và lỗi auth |
| `spring.mvc.problemdetails.enabled: true` | Không bật thì lỗi validation trả định dạng cũ của Boot ⇒ API nói **hai** thứ tiếng |
| Exception nghiệp vụ kế thừa `ErrorResponseException` | Mỗi exception tự mang status + body. Lợi ích phụ: nó là `RuntimeException` nên **tự rollback** |
| `InsufficientFundsException` **không** mang số dư | Cùng exception này sẽ dùng cho hoàn tiền ở giai đoạn 2, nơi tài khoản bị trừ thuộc về **người khác** |

### Kiểm thử

| Quyết định | Đã loại | Vì sao |
|---|---|---|
| **Toàn bộ là integration test** với Testcontainers | Unit test + mock repository | Thứ dự án này dạy nằm ở **chỗ ghép nối**: Hibernate ↔ SQL, transaction ↔ exception, khoá ↔ đồng thời. Mock che đúng những chỗ đó |
| Một container cho **cả JVM**, khởi tạo trong `static` | `@Testcontainers` + `@Container` của JUnit | Extension của JUnit sở hữu container **theo từng class** và tắt ở `afterAll`, trong khi Spring cache context dùng chung ⇒ từ class thứ hai trở đi, DataSource trỏ vào port đã chết ([bug #4](NHAT_KY_BUG.md)) |
| Test đồng thời **khẳng định cả hai chiều** | Chỉ kiểm "không âm" | Dưới 0 thì `CHECK` bắt được. **Trên 0** mới là lost update |
| Đo bằng `<testcase time>` trong XML | Dòng `Time elapsed` của Maven | Dòng của Maven gộp cả ~100 giây khởi động Spring context |

---

## 5. Ba lớp bảo vệ nằm trong cơ sở dữ liệu

Không lớp nào phụ thuộc vào code Java chạy đúng:

| # | Bảo vệ | Cơ chế |
|---|---|---|
| 1 | Ví không bao giờ âm | `CHECK ck_wallet_non_negative` |
| 2 | Sổ cái chỉ ghi thêm | Trigger từ chối `UPDATE`/`DELETE` |
| 3 | Mọi giao dịch cân bằng | `CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`, kiểm lúc COMMIT |

Giữ **cả ba** dù chúng chồng lấn với kiểm tra ở tầng Java. Lý do: chúng đúng kể cả với SQL chạy tay,
với migration tương lai viết sai, và với bug trong chính code này.

---

## 6. Môi trường — quyết định bất thường nhưng có lý do

Máy phát triển **không có JDK, không có Maven**. Mọi lệnh build chạy trong container:

```powershell
docker run --rm -v "D:/wallet-ledger/backend:/app" -v "wallet-m2:/root/.m2" `
  -v "//var/run/docker.sock:/var/run/docker.sock" `
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true `
  --add-host host.docker.internal:host-gateway -w /app `
  maven:3.9-eclipse-temurin-21 mvn -B verify
```

Cái giá phải trả, đã trả rồi:

| Vấn đề | Cách xử lý |
|---|---|
| Testcontainers bên trong container cần thấy Docker host | Mount socket + `TESTCONTAINERS_HOST_OVERRIDE` |
| Ryuk treo trong mô hình socket lồng nhau | Tắt Ryuk, bù bằng `addShutdownHook` ([bug #1](NHAT_KY_BUG.md)) |
| PowerShell cắt `-Dit.test=Foo` ở dấu chấm | **Luôn bọc ngoặc kép** ([bug #2](NHAT_KY_BUG.md)) |
| `Select-Object -First N` sinh exit code 255 giả | Ghi log ra file rồi lọc ([bug #6](NHAT_KY_BUG.md)) |

Lợi ích đi kèm: build **hoàn toàn tái lập được**. Không có "máy em chạy được".

---

## 7. Trạng thái thật — cái gì có, cái gì chưa

| | |
|---|---|
| Test | **80**, 0 fail, 0 skipped, 18 class |
| Migration | V1 auth · V2 sổ cái · V3 refresh token · V4 idempotency + audit |
| Giai đoạn | 1A ✅ · 1B ✅ · 1C–4 chưa |

**Chưa có:**

- **Frontend.** Chỉ có API.
- **Chưa từng chạy ngoài test.** Mọi test dựng DB bằng Testcontainers rồi xoá. `docker compose up`
  chưa chạy lần nào, nên chưa biết app khởi động được với database dài hạn hay không.
- **Chưa deploy, chưa có CI.**
- **Chưa có số coverage.** JaCoCo chưa cấu hình. ArchUnit là dependency **chưa có luật nào**.
- **Chưa có sao kê, hoàn tiền, Kafka** — giai đoạn 2.
- **Khoá idempotency và dòng audit không bao giờ được dọn** — không TTL, không job.

---

## 8. Nếu bị hỏi

**"Vì sao em chọn đề tài ví điện tử?"**
Vì dự án trước của em là CRUD từ điển, và nó không dạy được xử lý đồng thời — hai người sửa cùng một
từ thì ai ghi sau thắng, không ai mất gì. Với ví tiền, hai người rút cùng lúc là mất tiền thật. Em
muốn học transaction và khoá ở mức phải chứng minh được, nên chọn bài toán mà sai là thấy ngay.

**"Kiến trúc của em thế nào?"**
Chia package theo tính năng chứ không theo tầng, và điểm quan trọng nhất là **chốt chặn**: mọi thay
đổi số dư đi qua đúng một method `LedgerPostingService.post`. Nó khoá hai tài khoản theo thứ tự id
tăng dần, đổi số dư, ghi cặp bút toán. Ba bất biến quan trọng nhất thì em đẩy xuống **cơ sở dữ
liệu** — CHECK ví không âm, trigger sổ cái append-only, và constraint trigger DEFERRABLE kiểm cân
bằng lúc COMMIT — nên chúng đúng kể cả khi code có bug.

**"Quyết định nào em thấy khó nhất?"**
Bỏ Redis khỏi phần idempotency. Phản xạ đầu là dùng Redis cho nhanh, nhưng khoá idempotency và bút
toán phải commit nguyên tử, mà hai kho lưu trữ không có commit chung — nên luôn có khe hở hoặc trừ
tiền hai lần, hoặc nuốt mất request. Em chấp nhận chậm hơn để đổi lấy tính đúng, và ghi lại lý do
để không ai mở lại tranh luận đó.

**"Em test thế nào?"**
80 test, toàn bộ là integration test chạy trên PostgreSQL thật qua Testcontainers. Em cố ý không
dùng unit test với mock repository, vì thứ dự án này cần chứng minh nằm đúng ở chỗ ghép nối —
Hibernate với SQL, transaction với exception, khoá với đồng thời — và mock che mất chính những chỗ
đó. Hai test nặng nhất là 150 luồng rút tiền và 100 lệnh chuyển ngược chiều.
