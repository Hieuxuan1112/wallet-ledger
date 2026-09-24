# Triển khai lên Oracle VM

Runbook cho phần **chỉ bạn làm được**: tạo VM, mở cổng, xin chứng chỉ TLS, đặt secret. Mọi lệnh
chạy trên máy nào đều ghi rõ. Không bước nào yêu cầu gửi mật khẩu hay khoá SSH cho ai khác —
secret chỉ nằm trong `.env` trên VM và trong *GitHub → Settings → Secrets*.

Kiến trúc chạy trên VM: `docker-compose.yml` + `docker-compose.prod.yml` (3 bản `api`, nginx nghe
443, certbot tự gia hạn). Lý do chọn: spec §11 — Kafka vẫn chạy thật, không cần đổi kiến trúc,
miễn phí lâu dài.

## 1. Tạo VM

- Oracle Cloud → Compute → Instances → Create.
- Shape `VM.Standard.A1.Flex`, **2 OCPU / 12 GB** (Always Free hiện tại, spec §11), image
  **Ubuntu 24.04 (aarch64)**.
- Tải cặp SSH key Oracle sinh ra, hoặc dán public key của bạn. Private key ở lại máy bạn.
- Nếu báo *Out of capacity*: thử lại vài giờ sau hoặc chọn availability domain khác. Không có
  cách nào khác — capacity ARM phụ thuộc vùng. Phương án dự phòng của spec (Render + Neon, tắt
  Kafka bằng `LoggingEventPublisher`) chưa được dựng ở phase này.

## 2. Mở cổng 80 và 443 (và chỉ hai cổng đó)

1. VCN → Security Lists → thêm Ingress: TCP, nguồn `0.0.0.0/0`, cổng đích `80` và `443`.
2. Image Ubuntu của Oracle còn chặn bằng iptables, nên trên VM:

```bash
sudo iptables -I INPUT 6 -p tcp -m state --state NEW -m multiport --dports 80,443 -j ACCEPT
sudo netfilter-persistent save
```

**Không mở 55433 (Postgres) và 19092 (Kafka).** Compose đã bind hai cổng này vào `127.0.0.1`.

## 3. Cài Docker trên VM

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
```

Đăng xuất, đăng nhập lại, kiểm tra `docker compose version`.

## 4. Tên miền và chứng chỉ TLS

Chưa có domain thì dùng `<ip-viết-bằng-dấu-gạch>.sslip.io` (ví dụ `144-24-1-2.sslip.io`): tên này
trỏ về đúng IP và Let's Encrypt cấp chứng chỉ thật cho nó. Nếu bị giới hạn tần suất, một tên
DuckDNS miễn phí dùng y hệt.

Trên VM, tạo thư mục và `.env` (bước 5), rồi xin chứng chỉ lần đầu **khi cổng 80 còn trống**
(tức là trước khi khởi động stack). Lệnh này bạn tự chạy vì nó chấp nhận điều khoản Let's Encrypt:

```bash
export SERVER_NAME=<tên-miền-của-bạn>
docker run --rm -p 80:80 -v wallet-ledger_letsencrypt:/etc/letsencrypt certbot/certbot:v5.8.0 \
  certonly --standalone -d "$SERVER_NAME" -m <email-của-bạn> --agree-tos -n \
  --deploy-hook "chown -R 101:101 /etc/letsencrypt/live /etc/letsencrypt/archive"
```

`chown 101` vì nginx bản unprivileged chạy bằng uid 101 và không đọc được khoá riêng của root.
Hook này được certbot lưu lại và chạy lại mỗi lần gia hạn.

nginx chỉ nạp chứng chỉ mới khi reload, nên thêm vào crontab của VM (`crontab -e`):

```
0 4 * * 1 cd ~/wallet-ledger && docker compose -f docker-compose.yml -f docker-compose.prod.yml exec -T web nginx -s reload
```

## 5. `.env` trên VM

```bash
mkdir -p ~/wallet-ledger && cd ~/wallet-ledger
# copy .env.example từ repo vào đây (scp hoặc dán tay), rồi:
```

Điền: `WEB_PORT=80`, `SERVER_NAME=<tên miền>`, `POSTGRES_PASSWORD=$(openssl rand -hex 24)`,
`APP_JWT_SECRET=$(openssl rand -base64 48)`. Rồi `chmod 600 .env`.

Lưu ý: `POSTGRES_PASSWORD` chỉ có tác dụng **lần đầu volume được tạo**. Đổi sau này phải làm theo
mục 11.

## 6. Khoá triển khai cho GitHub Actions

Trên máy bạn:

```bash
ssh-keygen -t ed25519 -f wallet-deploy -C github-actions -N ""
```

- Nội dung `wallet-deploy.pub` → thêm một dòng vào `~/.ssh/authorized_keys` trên VM.
- `ssh-keyscan <ip-hoặc-tên-miền>` → dán kết quả vào secret **`VM_KNOWN_HOSTS`**.
- Nội dung `wallet-deploy` (private) → secret **`VM_SSH_KEY`**. Xoá file này khỏi máy sau khi dán.
- Secret **`VM_HOST`** (IP hoặc tên miền), **`VM_USER`** (thường là `ubuntu`).
- Variables (không phải secrets): **`PUBLIC_HOST`** = tên miền, rồi cuối cùng **`DEPLOY_ENABLED`** = `true`.
- Settings → Environments → tạo `production` (nên bật *Required reviewers* là chính bạn).

Không có secret registry: GHCR dùng `GITHUB_TOKEN` của workflow.

## 7. GHCR

Sau lần chạy đầu của job `images`, vào từng package (`wallet-ledger-api`, `wallet-ledger-web`) →
Package settings → **Change visibility → Public**. VM kéo image không cần đăng nhập.

## 8. Deploy lần đầu

Merge vào `main` → job `deploy` chạy → kết thúc bằng smoke test vào URL thật. Chạy tay để kiểm tra:

```bash
bash scripts/smoke.sh https://<tên-miền>
```

## 9. Backup

Crontab trên VM:

```
15 3 * * * ~/wallet-ledger/scripts/backup-db.sh
```

Script giữ 14 ngày trong `~/wallet-ledger/backups/`. **Một bản backup nằm cùng ổ đĩa với dữ liệu
chưa phải backup**: định kỳ chép thư mục này về máy bạn (`scp`) hoặc lên OCI Object Storage.
Khôi phục:

```bash
docker compose exec -T db pg_restore -U wallet -d wallet --clean < backups/wallet-<ngày>.dump
```

## 10. Rollback

Mỗi lần deploy ghi một dòng vào `~/wallet-ledger/releases.log` (thời gian + SHA). Quay lại bản trước:

```bash
cd ~/wallet-ledger
IMAGE_TAG=<sha-bản-trước> docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-build --wait
```

Flyway chỉ đi tới. Quay ngược qua một migration cần khôi phục từ backup — đó là lý do mọi
migration cho tới nay đều chỉ **thêm** (bảng/cột mới), không xoá hay đổi kiểu.

## 11. Xoay vòng secret

- **JWT**: đặt giá trị mới trong `.env`, chạy lại `up -d` (api được tạo lại). Mọi phiên đăng nhập
  chấm dứt, người dùng đăng nhập lại.
- **Mật khẩu DB**: đổi trong Postgres **trước**, sửa `.env` **sau**, rồi `up -d`:
  `docker compose exec db psql -U wallet -c "ALTER USER wallet PASSWORD '<mới>'"`.
- **Khoá deploy**: sinh cặp mới, thêm public key mới, cập nhật secret `VM_SSH_KEY`, xoá dòng cũ
  trong `authorized_keys`.

## 12. Oracle có thể thu hồi VM rảnh

Instance Always Free có CPU rất thấp liên tục nhiều ngày có thể bị Oracle thu hồi. Nâng tài khoản
lên Pay-As-You-Go (vẫn miễn phí trong hạn mức) loại bỏ rủi ro này. Oracle cũng đã giảm hạn mức
Always Free ARM vào 2026-06-15 mà không báo trước (spec §12) — đừng coi nó là vĩnh viễn.
