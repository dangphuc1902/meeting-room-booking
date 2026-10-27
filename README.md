# Meeting Room Booking API

Backend REST API cho hệ thống đặt phòng họp nội bộ (~10 phòng, ~200 nhân viên).
Mục tiêu chính: **loại bỏ hoàn toàn việc trùng lịch**, kể cả khi nhiều người đặt cùng lúc.

> Trạng thái: nền tảng (schema, bảo mật, xử lý lỗi, Docker, test hạ tầng) đã xong.
> Các mục đánh dấu **TODO** đang được phát triển.

---

## 1. Chạy nhanh

Yêu cầu: Docker Desktop (hoặc Docker Engine + Compose v2).

```bash
docker compose up --build
```

| Thành phần | URL |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |

Tài khoản demo (profile `demo`, bật mặc định trong compose):

| Vai trò | Email | Mật khẩu |
|---|---|---|
| ADMIN | `admin@company.local` | `Admin@123` |
| USER | `user@company.local` | `User@123` |

Profile `demo` còn seed sẵn 10 phòng họp. Muốn chạy với database trống: `SPRING_PROFILES_ACTIVE=default docker compose up`.
Mọi cấu hình đều có giá trị mặc định; xem `.env.example` để tùy chỉnh (copy thành `.env`).

```bash
# Đăng nhập lấy token
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@company.local","password":"Admin@123"}'

# Gọi API với token
curl -s http://localhost:8080/api/v1/auth/me -H "Authorization: Bearer <accessToken>"
```

### Chạy local khi phát triển (không build image)

```bash
docker compose up -d db          # chỉ chạy PostgreSQL
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

### Chạy test

```bash
mvn verify        # cần Docker đang chạy: integration test dùng Testcontainers (PostgreSQL thật)
```

> Docker Engine 29+ yêu cầu Docker API ≥ 1.44; `src/test/resources/docker-java.properties` cố định `api.version=1.44` để Testcontainers kết nối được.

---

## 2. Tech stack

| Thành phần | Lựa chọn | Lý do |
|---|---|---|
| Ngôn ngữ | Java 21 (LTS) | Records, pattern matching; LTS dài hạn |
| Framework | Spring Boot 3.5 | Theo yêu cầu đề |
| Database | PostgreSQL 16 | `EXCLUDE` constraint + `tstzrange` giải bài toán trùng lịch ngay tại DB |
| Migration | Flyway | Schema có version, review được; Hibernate chỉ `validate` |
| ORM | Spring Data JPA (Hibernate 6) | |
| Bảo mật | Spring Security + OAuth2 Resource Server (JWT HS256) | Dùng cơ chế validate JWT có sẵn của Spring, không tự viết filter |
| Tài liệu API | springdoc-openapi (Swagger UI) | Người review thử API ngay trên trình duyệt |
| Lỗi | RFC 7807 `ProblemDetail` | Định dạng lỗi chuẩn, thống nhất cho mọi endpoint |
| Test | JUnit 5, AssertJ, MockMvc, **Testcontainers** | Test trên PostgreSQL thật; H2 không hỗ trợ `EXCLUDE` |

---

## 3. Nghiệp vụ và giả định

| # | Quy tắc | Ghi chú |
|---|---|---|
| R1 | Hai booking `CONFIRMED` cùng phòng không được giao nhau | Khoảng nửa mở `[start, end)`: 9:00–10:00 và 10:00–11:00 **không** trùng |
| R2 | Không đặt trong quá khứ | |
| R3 | `end > start` | Có CHECK constraint ở DB |
| R4 | Thời lượng 15 phút – 8 giờ | `app.booking.min-duration`, `max-duration` |
| R5 | Mốc giờ là bội số 15 phút | `app.booking.slot-minutes` |
| R6 | Chỉ trong giờ làm việc 07:00–20:00, thứ 2 – thứ 6 | Tính theo `Asia/Ho_Chi_Minh` |
| R7 | Đặt trước tối đa 90 ngày | Tránh giữ chỗ vô thời hạn |
| R8 | Số người tham dự ≤ sức chứa phòng | |
| R9 | Hủy: chủ booking hoặc ADMIN; không hủy booking đã kết thúc | Soft cancel (`CANCELLED`), giữ lịch sử |
| R10 | Sửa: chỉ booking chưa bắt đầu; vẫn phải thỏa R1–R8 | Optimistic locking (`version`) |
| R11 | Phòng ngừng hoạt động thì không nhận booking mới | Không xóa cứng phòng |
| R12 | Lưu thời gian dạng `timestamptz` (UTC); API dùng ISO-8601 có offset | |

Không có tự đăng ký: hệ thống nội bộ, ADMIN tạo tài khoản; admin đầu tiên được tạo từ cấu hình khi khởi động.

Các quy tắc R4–R7 cấu hình được trong `application.yml` (`app.booking.*`).

### Ngoài phạm vi (có chủ đích)
- SSO/LDAP, đồng bộ Google Calendar/Outlook
- Gửi email/Slack thông báo
- Booking lặp lại (recurring) — **TODO nếu kịp**

---

## 4. Kiến trúc

**Modular monolith**, tổ chức package theo tính năng:

```
com.company.meetingroom
├── common/        error (ProblemDetail, exception), config (OpenAPI), time (Clock, ZoneId)
├── auth/          SecurityConfig, JWT, login, AuthUser
├── user/          User, Role, bootstrap tài khoản
├── room/          Room, Equipment
└── booking/       Booking, BookingProperties (luật nghiệp vụ cấu hình được)
```

Lý do:
- 200 người dùng, 10 phòng: một service + một database là đủ và đơn giản để vận hành.
  Tách microservice sẽ phát sinh bài toán nhất quán phân tán mà không đem lại lợi ích.
- Ràng buộc chống trùng lịch cần nằm trong **một** database.
- Package theo tính năng giúp tách module sau này nếu cần.
- `java.time.Clock` được inject thay vì gọi `Instant.now()`, để test các quy tắc thời gian một cách tất định.

---

## 5. Database

```
users 1───* bookings *───1 rooms 1───* room_equipment
```

Ràng buộc quan trọng nhất (`V1__init_schema.sql`):

```sql
CONSTRAINT no_overlapping_bookings EXCLUDE USING gist (
    room_id WITH =,
    tstzrange(start_time, end_time, '[)') WITH &&
) WHERE (status = 'CONFIRMED')
```

- `'[)'`: nửa mở, các cuộc họp nối tiếp nhau hợp lệ.
- `WHERE status = 'CONFIRMED'`: hủy booking là giải phóng slot ngay, không cần xóa row.
- `btree_gist`: cần để dùng cột scalar `room_id` trong index GiST.
- Index GiST của constraint cũng phục vụ luôn truy vấn "booking của phòng X giao với khoảng [a, b)".

---

## 6. Các quyết định quan trọng

### Chống trùng lịch bằng `EXCLUDE` constraint thay vì kiểm tra ở tầng ứng dụng

| Cách | Vấn đề |
|---|---|
| `SELECT` kiểm tra rồi `INSERT` | Race condition: hai request cùng qua bước kiểm tra |
| `SERIALIZABLE` | Đúng nhưng phải retry khi serialization failure |
| `SELECT ... FOR UPDATE` / advisory lock | Đúng nếu **mọi** code path đều nhớ lock |
| **`EXCLUDE` constraint** | DB đảm bảo bất biến; không thể bỏ qua, kể cả nhiều instance app |

Ứng dụng vẫn kiểm tra trước để trả thông báo thân thiện ("phòng đã được X đặt 9:00–10:00"),
nhưng **tính đúng đắn do DB đảm bảo**: lỗi SQLSTATE `23P01` được chuyển thành HTTP 409.
`NoOverlapConstraintIT` chứng minh: 20 luồng đặt cùng slot, đúng 1 thành công.

### Thời gian: `timestamptz` + khoảng nửa mở
Lưu UTC, tránh lỗi múi giờ; quy tắc "giờ làm việc" và "một ngày" tính theo múi giờ công ty (`app.zone-id`).

### Soft cancel
Giữ lịch sử ai hủy, khi nào, vì sao; partial constraint giúp slot được giải phóng ngay.

### JWT stateless, roles trong token
Token sống 8 giờ (một ngày làm việc). Đánh đổi: vô hiệu hóa user không thu hồi token đang có
cho tới khi hết hạn. Cải tiến khi lên production: token ngắn hạn + refresh token, hoặc kiểm tra `active` mỗi request.

### Schema do Flyway quản lý
`ddl-auto=validate`: Hibernate chỉ kiểm tra entity khớp schema. Dữ liệu demo là repeatable migration
riêng (`db/demo`), chỉ chạy với profile `demo`, không lẫn vào migration production.

### Lỗi theo RFC 7807
Mọi lỗi, kể cả 401/403 phát sinh trong security filter, đều trả `application/problem+json` cùng một định dạng.

| Trường hợp | HTTP |
|---|---|
| Dữ liệu đầu vào sai định dạng | 400 |
| Chưa đăng nhập / không đủ quyền | 401 / 403 |
| Không tìm thấy | 404 |
| Trùng lịch, sửa đồng thời | 409 |
| Vi phạm quy tắc nghiệp vụ | 422 |

---

## 7. API

Base path: `/api/v1`. Chi tiết đầy đủ trên Swagger UI.

| Method | Path | Quyền | Trạng thái |
|---|---|---|---|
| POST | `/auth/login` | Public | Done |
| GET | `/auth/me` | User | Done |
| GET | `/rooms?includeInactive=` | User (`includeInactive` chỉ có tác dụng với Admin) | Done |
| GET | `/rooms/{id}` | User | Done |
| POST/PUT | `/rooms`, `/rooms/{id}` | Admin | Done |
| PATCH | `/rooms/{id}/status` | Admin | Done |
| GET | `/rooms/availability?start=&end=&minCapacity=&equipment=` | User (phòng nhỏ nhất vừa đủ đứng đầu; `+` của offset phải gửi là `%2B`) | Done |
| GET | `/rooms/{id}/schedule?from=&to=` | User | TODO |
| GET | `/schedule?date=` | User | TODO |
| POST | `/bookings` | User | Done |
| GET/PUT | `/bookings/{id}` | Owner/Admin (PUT cần gửi `version` vừa đọc) | Done |
| POST | `/bookings/{id}/cancel` | Owner/Admin | Done |
| GET | `/bookings/me` | User | TODO |
| GET | `/bookings` | Admin | TODO |
| CRUD | `/users` | Admin | TODO |

---

## 8. Kiểm thử

| Test | Nội dung |
|---|---|
| `NoOverlapConstraintIT` | Ràng buộc DB: chồng lấn bị chặn, nối tiếp hợp lệ, hủy giải phóng slot, **20 request đồng thời chỉ 1 thành công** |
| `AuthFlowIT` | Đăng nhập, dùng token, lỗi 400/401 đúng định dạng RFC 7807 |

---

## 9. Hướng phát triển khi đưa vào production
- Thông báo email/Slack khi được mời họp hoặc bị hủy
- Tích hợp SSO của công ty (OIDC), bỏ đăng nhập mật khẩu
- Check-in và tự giải phóng phòng nếu không check-in sau 10–15 phút
- Audit log, rate limiting, metrics (Micrometer + Prometheus)
- Refresh token / thu hồi token
