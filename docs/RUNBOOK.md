# Runbook — Meeting Room Booking API

Hướng dẫn vận hành cho môi trường chạy bằng `docker compose` (một máy, hai container).
Mọi lệnh chạy ở thư mục gốc repo. Trên Windows dùng Git Bash; với lệnh có đường dẫn `/tmp/...`
trong container, đặt `MSYS_NO_PATHCONV=1` để Git Bash không đổi đường dẫn.

| Thành phần | Container | Ghi chú |
|---|---|---|
| API (Spring Boot) | `meeting-room-app` | Cổng `${APP_PORT:-8080}` |
| PostgreSQL 16 | `meeting-room-db` | Chỉ mở trên `127.0.0.1:${DB_PORT:-5432}`; dữ liệu ở volume `pgdata` |

Cấu hình đọc từ `.env` (mẫu: `.env.example`). Không commit `.env`.

---

## 1. Khởi động, dừng, cập nhật

```bash
docker compose up --build -d        # khởi động (lần đầu hoặc sau khi đổi code)
docker compose ps                    # trạng thái; db phải "healthy" trước khi app chạy
docker compose stop                  # dừng, giữ dữ liệu
docker compose down                  # xóa container, GIỮ volume dữ liệu
docker compose down -v               # XÓA LUÔN DỮ LIỆU — chỉ dùng khi chắc chắn
```

Cập nhật phiên bản mới:

1. Backup database (mục 3).
2. `git pull`, rồi `docker compose up --build -d app`.
3. Flyway tự chạy migration mới khi app khởi động. Nếu migration lỗi, app không lên: xem log (mục 2), không sửa tay
   bảng `flyway_schema_history`; khôi phục từ bản backup và quay lại phiên bản cũ.

App tắt êm (`server.shutdown: graceful`): request đang xử lý được hoàn tất trước khi dừng.

## 2. Kiểm tra sức khỏe và xem log

```bash
curl http://localhost:8080/actuator/health             # {"status":"UP"}; tính cả kết nối DB
curl http://localhost:8080/actuator/health/liveness    # tiến trình còn sống (probe cho orchestrator)
curl http://localhost:8080/actuator/health/readiness   # app đã khởi động xong, sẵn sàng nhận request

docker compose logs -f app           # log app
docker compose logs --since 1h db    # log PostgreSQL trong 1 giờ qua
```

Mức log mặc định là `INFO` (profile `demo`: `DEBUG` cho `com.company.meetingroom`). Muốn bật `DEBUG` tạm thời,
thêm vào `environment` của service `app` (hoặc `docker-compose.override.yml`):
`LOGGING_LEVEL_COM_COMPANY_MEETINGROOM: DEBUG`, rồi `docker compose up -d app`.

Lỗi trả cho client không kèm stack trace (`include-stacktrace: never`); chi tiết lỗi chỉ nằm trong log app.

## 3. Backup và restore

### Backup

```bash
mkdir -p backups
export MSYS_NO_PATHCONV=1
docker exec meeting-room-db pg_dump -U meeting -d meeting_room -Fc -f /tmp/backup.dump
docker cp meeting-room-db:/tmp/backup.dump backups/meeting_room_$(date +%Y%m%d_%H%M).dump
```

- `-Fc`: định dạng nén của PostgreSQL, restore chọn lọc được bằng `pg_restore`.
- Ghi file trong container rồi `docker cp` ra ngoài, không dùng `>`: trên PowerShell `>` đổi encoding và làm hỏng file nhị phân.
- Đổi `meeting`/`meeting_room` nếu `.env` dùng `DB_USER`/`DB_NAME` khác.
- Production: chạy định kỳ (cron / Task Scheduler), lưu bản sao ra ngoài máy, giữ tối thiểu 7 bản gần nhất,
  và **thử restore định kỳ** — bản backup chưa từng restore thì chưa chắc dùng được.

### Restore

```bash
export MSYS_NO_PATHCONV=1
docker compose stop app                                         # tránh ghi trong lúc restore
docker cp backups/<file>.dump meeting-room-db:/tmp/restore.dump
docker exec meeting-room-db pg_restore -U meeting -d meeting_room --clean --if-exists --no-owner /tmp/restore.dump
docker compose start app
curl http://localhost:8080/actuator/health
```

`--clean --if-exists` xóa đối tượng hiện có rồi tạo lại từ bản backup (gồm cả lịch sử Flyway và constraint chống trùng lịch).
Muốn kiểm tra bản backup mà không đụng dữ liệu thật: tạo database tạm (`createdb`) rồi `pg_restore -d <db tạm>`.

## 4. Secret và tài khoản

| Biến (`.env`) | Ý nghĩa |
|---|---|
| `JWT_SECRET` | Khóa ký JWT (HS256), ≥ 32 byte. Tạo: `openssl rand -base64 48` |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Admin đầu tiên, chỉ được tạo khi hệ thống **chưa có admin nào** |
| `DB_PASSWORD` | Mật khẩu PostgreSQL |

Ngoài profile `demo`, thiếu `JWT_SECRET` hoặc `ADMIN_PASSWORD` thì app **không khởi động** (cố ý, để không chạy với giá trị mặc định).
Compose đặt sẵn giá trị demo; production phải ghi đè trong `.env`.

**Xoay JWT secret** (nghi lộ, hoặc định kỳ): đổi `JWT_SECRET` trong `.env` → `docker compose up -d app`.
Mọi token cũ mất hiệu lực ngay, người dùng chỉ cần đăng nhập lại; không mất dữ liệu.

**Quên mật khẩu admin**: một admin khác đặt lại qua `PUT /api/v1/users/{id}/password`.
Đổi `ADMIN_PASSWORD` trong `.env` **không** đổi mật khẩu admin đã tồn tại.

**Nhân viên nghỉ việc**: `PATCH /api/v1/users/{id}/status` với `{"active":false}`. Tài khoản không đăng nhập được,
token đang có không tạo / sửa / hủy booking được nữa; nếu là admin, token cũ cũng mất quyền gọi API admin ngay. Booking sắp tới của họ không tự hủy:
xem bằng `GET /api/v1/bookings?userId={id}` và hủy nếu cần.

## 5. Xử lý sự cố

| Triệu chứng | Kiểm tra | Cách xử lý |
|---|---|---|
| App không lên, log có `app.jwt.secret must be at least 32 bytes` hoặc `app.bootstrap.admin.password ... must not be blank` | `.env` | Đặt `JWT_SECRET` (≥ 32 byte), `ADMIN_PASSWORD` |
| App không lên, log có `Connection refused` / `Connection to db:5432` | `docker compose ps` — db có `healthy`? | `docker compose logs db`; `docker compose up -d db`. Đầy ổ đĩa: `docker system df` |
| App không lên, log có `FlywayValidateException` / `Migration ... failed` | `SELECT * FROM flyway_schema_history ORDER BY installed_rank` | Không sửa tay migration đã chạy; restore backup và quay lại bản trước, sửa bằng migration mới |
| `/actuator/health` DOWN (chi tiết `db`), `liveness` UP | DB tạm mất kết nối | Thường tự hồi phục khi DB trở lại (Hikari tự kết nối lại); nếu không, `docker compose restart app` |
| `Bind for 0.0.0.0:8080 failed: port is already allocated` | Cổng bị chiếm | Đổi `APP_PORT` (hoặc `DB_PORT`) trong `.env` |
| Nhiều 409 `booking-overlap` | Bình thường khi tranh slot | Constraint `no_overlapping_bookings` đang làm đúng việc; không tắt |
| Người dùng báo 401 hàng loạt sau khi deploy | `JWT_SECRET` có bị đổi? | Đăng nhập lại; nếu đổi ngoài ý muốn, khôi phục giá trị cũ |
| Hết admin đăng nhập được | — | Ứng dụng luôn giữ ≥ 1 admin đang hoạt động. Nếu vẫn mất (DB bị sửa tay): `UPDATE users SET active = true WHERE email = '<admin>';` qua `psql` |

Mở `psql`:

```bash
docker exec -it meeting-room-db psql -U meeting -d meeting_room
```

## 6. Kiểm tra sau khi triển khai

```bash
curl -s http://localhost:8080/actuator/health
npx newman run postman/meeting-room-booking.postman_collection.json   # 70 request, 157 assertion
```

Collection tự tạo phòng và user riêng rồi dọn dẹp (hủy booking, khóa phòng / user), nên chạy được trên môi trường đang dùng.
