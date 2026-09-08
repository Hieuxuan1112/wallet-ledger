# Học transaction và xử lý đồng thời qua wallet-ledger

Bộ tài liệu này dạy phần **khó nhất của backend** — thứ mà một app CRUD không dạy được — qua đúng
code trong repo này. Mọi ví dụ đều chỉ tới một file thật, và giải thích **vì sao chọn cách đó** chứ
không chỉ "làm thế nào".

Repo này: **80 test**, 0 skipped, toàn bộ chạy trên PostgreSQL thật, **11 bug thật** được ghi lại
đầy đủ.

## Đọc theo thứ tự nào

| # | Tài liệu | Nội dung |
|---|---|---|
| 1 | [KIEN_TRUC_VA_QUYET_DINH.md](KIEN_TRUC_VA_QUYET_DINH.md) | Kiến trúc + **lý do** sau mỗi quyết định, kèm phương án đã loại |
| 2 | [HOC_SO_CAI_KE_TOAN.md](HOC_SO_CAI_KE_TOAN.md) | Sổ cái kép, bút toán có dấu, ba lớp bảo vệ trong DB, `NUMERIC` vs `double` |
| 3 | [HOC_DONG_THOI_VA_KHOA.md](HOC_DONG_THOI_VA_KHOA.md) | **Lý do dự án tồn tại.** Lost update, `FOR UPDATE`, deadlock, thứ tự khoá, cách đo đúng |
| 4 | [HOC_TRANSACTION_SPRING.md](HOC_TRANSACTION_SPRING.md) | Proxy, propagation, `REQUIRES_NEW`, `afterCommit`, bốn cái bẫy |
| 5 | [HOC_IDEMPOTENCY.md](HOC_IDEMPOTENCY.md) | Vì sao Postgres không Redis, tách hai lớp, bốn tình huống, hai lỗ hổng thật |
| 6 | [NHAT_KY_BUG.md](NHAT_KY_BUG.md) | **11 bug thật**: triệu chứng → giả thuyết sai → vì sao sai → cách sửa → bài học |

**Đường đi ngắn nhất nếu chỉ có 30 phút:** đọc tài liệu 3, rồi bug #7, #9, #10, #11 trong tài liệu 6.

## Đã có ở repo khác — KHÔNG đọc lại ở đây

Những phần dưới đây đã được viết ở hai repo trước, dùng chung, không lặp lại:

| Chủ đề | Đọc ở đâu | Ghi chú khi áp vào dự án này |
|---|---|---|
| Spring Boot: DI/IoC, bean, auto-configuration | [SlangWord](https://github.com/Hieuxuan1112/SlangWord) `docs/hoc/HOC_SPRING_BOOT.md` | Nền tảng bắt buộc. Tài liệu 4 ở đây **chỉ** đào sâu phần transaction |
| JPA/Hibernate: quan hệ, lazy/eager, N+1 | SlangWord `docs/hoc/HOC_JPA_HIBERNATE.md` | Dự án này cố ý **không** dùng `@ManyToOne` trong sổ cái — lý do ở tài liệu 1 |
| REST API: status code, versioning, error contract | SlangWord `docs/hoc/HOC_REST_API_DESIGN.md` | RFC 7807 giống nhau. Phần mới ở đây: header `Idempotency-Key` |
| Spring Security: filter chain, JWT, refresh token | SlangWord `docs/hoc/HOC_SPRING_SECURITY.md` | Dự án này dùng **y hệt** kiến trúc auth đó |
| Testing: JUnit 5, Mockito, tháp test, coverage | SlangWord `docs/hoc/HOC_TESTING_JAVA.md` | Khác biệt: dự án này **toàn bộ là integration test**, lý do ở tài liệu 1 |
| CI/CD, quản lý secret, quét lỗ hổng | SlangWord `docs/hoc/HOC_CICD_VA_BAO_MAT.md` | Dự án này **chưa có CI** — giai đoạn 3 |
| SQL: join, CTE, window function, index, transaction | [travel-ai-agent](https://github.com/Hieuxuan1112/travel-ai-agent) `HOC_SQL.md` | **Đọc trước tài liệu 2 và 3.** Không hiểu SQL thì không debug được khoá |
| Big-O, cấu trúc dữ liệu, OOP/SOLID | travel-ai-agent `HOC_DSA_OOP.md` | |
| Docker: image, multi-stage, non-root, Compose | travel-ai-agent `HOC_DOCKER.md` | |
| Kafka, event-driven, DLQ, offset | repo `eda-kafka-lab` | Sẽ dùng ở giai đoạn 2 của dự án này |

**Cách dùng gọn nhất:** đọc `HOC_SQL.md` bên travel-ai-agent trước → rồi quay về đây đọc 1 → 6.

## Cái này dạy được gì mà hai repo kia không

| | SlangWord (CRUD từ điển) | wallet-ledger |
|---|---|---|
| Ghi đồng thời | Ai ghi sau thắng, không ai mất gì | **Mất tiền thật** |
| Bất biến | Không có bất biến nào bị phá | `SUM(amount) = 0` phải đúng mọi lúc |
| Retry một request | Lỗi 409, vô hại | **Trừ tiền hai lần** |
| Khoá | Không cần | `SELECT FOR UPDATE`, thứ tự khoá, deadlock |
| Transaction | Đặt `@Transactional` lên service là xong | Lồng nhau, `REQUIRES_NEW`, `afterCommit`, 4 cái bẫy |

Nếu bạn đã đọc SlangWord rồi, **giá trị mới nằm ở tài liệu 3, 4, 5 và các bug #7 → #11.**

## Nguyên tắc viết

1. **Mọi ví dụ là code thật trong repo**, có đường dẫn. Không có đoạn code bịa.
2. **Luôn có phần "vì sao"** — và cả "vì sao không chọn cách kia".
3. **Bug thật được giữ nguyên**, kể cả những lần giả thuyết đầu tiên sai. 11 bug ở đây có 4 cái mà
   nếu không có bước đỏ TDD thì sẽ lọt xuống môi trường thật.
4. **Chỗ nào chưa làm thì nói rõ là chưa làm.** Mỗi tài liệu có mục "Chỗ chưa làm". Không giả vờ
   hoàn hảo.

## Câu hỏi phỏng vấn

Mỗi tài liệu kết thúc bằng mục **"Nếu bị hỏi"** — câu hỏi phỏng vấn thật về chủ đề đó, kèm câu trả
lời neo vào code repo này, có con số đo được.
