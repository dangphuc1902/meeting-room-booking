# Meeting Room Booking API

[![CI](https://github.com/dangphuc1902/meeting-room-booking/actions/workflows/ci.yml/badge.svg)](https://github.com/dangphuc1902/meeting-room-booking/actions/workflows/ci.yml)

Backend REST API cho hệ thống đặt phòng họp nội bộ (~10 phòng, ~200 nhân viên).
Mục tiêu chính: **loại bỏ hoàn toàn việc trùng lịch**, kể cả khi nhiều người đặt cùng lúc.

Chức năng: quản lý phòng, đặt / sửa / hủy phòng, xem lịch theo phòng và theo ngày, tìm phòng trống,
quản lý tài khoản. Hai vai trò: người dùng thường (`USER`) và quản trị (`ADMIN`).

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

> Cổng 8080 đã bị chương trình khác dùng? Copy `.env.example` thành `.env`, đổi `APP_PORT=8081`,
> chạy lại `docker compose up --build`, rồi thay `8080` bằng `8081` trong các URL trên.

Tài khoản demo (profile `demo`, bật mặc định trong compose):

| Vai trò | Email | Mật khẩu |
|---|---|---|
| ADMIN | `admin@company.local` | `Admin@123` |
| USER | `user@company.local` | `User@123` |

Profile `demo` còn seed sẵn 10 phòng họp và 9 cuộc họp trong 3 ngày làm việc kế tiếp
(xem ngay bằng `GET /api/v1/schedule?date=<ngày làm việc kế tiếp>`). Booking demo chỉ được tạo khi bảng booking còn trống.
Muốn chạy với database trống: đặt `SPRING_PROFILES_ACTIVE=default` trong `.env`.
Mọi cấu hình đều có giá trị mặc định; xem `.env.example` để tùy chỉnh (copy thành `.env`).

> Giá trị mặc định chỉ dành cho demo; production bắt buộc đặt `JWT_SECRET`, `ADMIN_PASSWORD`, nếu thiếu thì app không khởi động.
> PostgreSQL trong compose chỉ mở cổng trên `127.0.0.1`.

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
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

### Chạy test

```bash
./mvnw verify     # cần Docker đang chạy: integration test dùng Testcontainers (PostgreSQL thật)
```

Không cần cài Maven: `mvnw` / `mvnw.cmd` tự tải đúng phiên bản. Chỉ cần JDK 21.

> Docker Engine 29+ yêu cầu Docker API ≥ 1.44; `src/test/resources/docker-java.properties` cố định `api.version=1.44` để Testcontainers kết nối được.

### Kiểm thử toàn bộ API bằng Postman

`postman/meeting-room-booking.postman_collection.json` đi qua toàn bộ nghiệp vụ theo thứ tự
(đăng nhập → user → phòng → đặt phòng và R1–R12 → sửa / optimistic lock → phòng trống, lịch → hủy → ngừng hoạt động phòng),
70 request, 157 assertion. Ngày đặt phòng được tính tự động (ngày làm việc gần nhất, cách hôm nay ≥ 2 ngày).
Mỗi lần chạy tạo phòng và user riêng, cuối cùng hủy booking và khóa phòng, user đó, nên chạy lại bao nhiêu lần cũng được.

- Postman: Import file → **Run collection** (chạy theo thứ tự, không chạy lẻ từng request).
- Dòng lệnh: `npx newman run postman/meeting-room-booking.postman_collection.json`
- App không chạy ở cổng 8080 (ví dụ `APP_PORT=8081`): đổi biến collection `baseUrl`, hoặc thêm `--env-var baseUrl=http://localhost:8081`.

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
| R3 | `end > start` | Có thêm CHECK constraint ở DB |
| R4 | Thời lượng 15 phút – 8 giờ | `app.booking.min-duration`, `max-duration` |
| R5 | Mốc giờ là bội số 15 phút | `app.booking.slot-minutes` |
| R6 | Chỉ trong giờ làm việc 07:00–20:00, thứ 2 – thứ 6 | Tính theo `Asia/Ho_Chi_Minh` |
| R7 | Đặt trước tối đa 90 ngày | Tránh giữ chỗ vô thời hạn |
| R8 | Số người tham dự ≤ sức chứa phòng | |
| R9 | Hủy: chủ booking hoặc ADMIN; không hủy booking đã kết thúc | Soft cancel (`CANCELLED`), giữ lịch sử |
| R10 | Sửa: chỉ booking chưa bắt đầu; vẫn phải thỏa R1–R8 | Optimistic locking (`version`) |
| R11 | Phòng ngừng hoạt động thì không nhận booking mới | Không xóa cứng phòng |
| R12 | Lưu thời gian dạng `timestamptz` (UTC); API dùng ISO-8601 có offset | |

Các quy tắc R4–R7 cấu hình được trong `application.yml` (`app.booking.*`).
Vi phạm R2–R11 trả 422, trong body có `"rule": "R6"` để client biết chính xác luật nào bị vi phạm.

### Tài khoản

| # | Quy tắc |
|---|---|
| U1 | Không có tự đăng ký (hệ thống nội bộ): ADMIN tạo tài khoản kèm mật khẩu ban đầu, nhân viên tự đổi sau |
| U2 | Admin đầu tiên được tạo từ cấu hình khi khởi động (`ADMIN_EMAIL`, `ADMIN_PASSWORD` trong `.env`) |
| U3 | Email là tên đăng nhập: lưu chữ thường, duy nhất, không sửa được |
| U4 | Khóa tài khoản thay vì xóa (booking cũ vẫn tham chiếu); tài khoản bị khóa không đăng nhập được |
| U5 | Admin không tự hạ quyền hay tự khóa mình; hệ thống luôn còn ít nhất một admin đang hoạt động (kiểm tra bằng `SELECT ... FOR UPDATE` trên các admin đang hoạt động, nên hai admin hạ quyền nhau cùng lúc vẫn còn lại một) |

### Ai thấy gì trên lịch
Mọi nhân viên thấy giờ, tiêu đề và **người đặt** (để biết hỏi ai khi cần phòng). **Mô tả** cuộc họp
chỉ chủ booking và ADMIN thấy.

### Ngoài phạm vi (có chủ đích)
- SSO/LDAP, đồng bộ Google Calendar/Outlook
- Gửi email/Slack thông báo
- Booking lặp lại (recurring): nhiều trường hợp biên (một buổi bị trùng, sửa một buổi hay cả chuỗi),
  cần thống nhất nghiệp vụ trước khi làm

---

## 4. Kiến trúc

**Modular monolith**, tổ chức package theo tính năng:

```
com.company.meetingroom
├── common/   error   ApiException + 400/404/409/422, GlobalExceptionHandler (RFC 7807), PgErrors (SQLSTATE)
│             web     PageResponse      config  OpenAPI      time  Clock, ZoneId
├── auth/     SecurityConfig, TokenService (JWT), AuthController (login, me), AuthUser
├── user/     User, UserService, UserController (quản lý tài khoản), AccountBootstrap
├── room/     Room, Equipment, RoomService, RoomController (CRUD phòng)
└── booking/  Booking, BookingPolicy (R2–R11), BookingService, BookingController (đặt / sửa / hủy / danh sách)
              ScheduleService, ScheduleController (phòng trống, lịch phòng, lịch ngày)
```

Trong mỗi module: `Controller` (HTTP, quyền, validate input) → `Service` (transaction, nghiệp vụ)
→ `Repository` (Spring Data). DTO là `record`, entity không bao giờ ra khỏi tầng service.
Tìm phòng trống và xem lịch nằm trong `booking/` vì chúng đọc booking; `room/` không phụ thuộc `booking/` ở tầng Java.

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
- Index GiST của constraint dùng cho việc kiểm tra trùng khi ghi. Các truy vấn đọc hiện so sánh cột `start_time`/`end_time`
  trực tiếp nên chỉ tận dụng được phần `room_id` của index. Với ~10 phòng điều này không đáng kể; khi dữ liệu lớn
  sẽ chuyển sang so sánh `tstzrange(...) && tstzrange(:from, :to)` để dùng trọn index.

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

`BookingApiIT` kiểm tra thêm ở tầng service: 10 request đồng thời qua `BookingService`, đúng 1 booking được tạo,
9 request còn lại nhận 409 (không phải 500).

### Quy tắc nghiệp vụ tập trung trong `BookingPolicy`
Tạo và sửa booking gọi cùng một `BookingPolicy.check(...)`, nên R10 ("sửa vẫn phải thỏa R1–R8") đúng theo cấu trúc,
không phụ thuộc việc nhớ copy kiểm tra. Policy dừng ở lỗi đầu tiên, trả 422 với `type` riêng
(`booking-in-past`, `outside-working-hours`...) và số hiệu luật. Policy nhận `Clock` nên được unit test
(`BookingPolicyTest`, 21 case) mà không cần database hay chờ thời gian thật.

### Sửa đồng thời: optimistic locking có kiểm tra phía client
`PUT /bookings/{id}` bắt buộc gửi `version` vừa đọc. Khác `version` hiện tại → 409 `concurrent-modification`.
Nhờ vậy cả trường hợp "hai người mở cùng booking, người sau ghi đè người trước" (cách nhau vài phút)
cũng bị chặn, không chỉ hai request trùng đúng thời điểm (trường hợp đó do `@Version` của JPA chặn).

### Thời gian: `timestamptz` + khoảng nửa mở
Lưu UTC, tránh lỗi múi giờ; quy tắc "giờ làm việc" và "một ngày" tính theo múi giờ công ty (`app.zone-id`).
Input bắt buộc có offset (`2030-01-07T09:00:00+07:00`), thiếu offset → 400, để không phải đoán múi giờ.
Output trả theo múi giờ công ty (`+07:00`), client hiển thị luôn được.
"Một ngày" trên lịch là 00:00–24:00 giờ Việt Nam: booking kết thúc đúng 00:00 chỉ thuộc ngày hôm trước.

### Tìm phòng trống bằng một truy vấn
Phòng đang hoạt động, đủ chỗ, `NOT EXISTS` booking giao với khoảng cần tìm, tất cả trong một câu SQL,
thay vì tải hết booking về rồi lọc. Kết quả xếp **phòng nhỏ nhất vừa đủ** lên đầu
để nhóm 3 người không được gợi ý hội trường 30 chỗ trước. Lọc thiết bị chạy trong bộ nhớ vì chỉ có ~10 phòng.
Tìm phòng không áp R6 (giờ làm việc): đó là việc của bước đặt phòng, nơi trả thông báo lỗi chính xác.

### 400 hay 422?
400 khi request sai định dạng hoặc tham số tìm kiếm vô nghĩa (thiếu offset, `to` trước `from`, khoảng > 31 ngày).
422 khi request đúng định dạng nhưng vi phạm luật đặt phòng. Vì vậy `end ≤ start` khi **đặt phòng** là 422 (R3),
còn khi **tìm phòng trống** là 400.

### Danh sách: phân trang, thứ tự cố định
`/bookings/me`, `/bookings`, `/users` trả `PageResponse` (`content`, `page`, `size`, `totalElements`, `totalPages`),
tối đa 100 dòng mỗi trang. Thứ tự sắp xếp cố định (theo giờ bắt đầu / theo tên): cho client tự chọn trường sort
thì gõ sai tên trường sẽ thành lỗi 500, và có thể sort theo cột không có index.

### Soft cancel, soft delete
Giữ lịch sử ai hủy, khi nào, vì sao; partial constraint giúp slot được giải phóng ngay.
Phòng và tài khoản cũng chỉ bị khóa (`active = false`), không xóa, vì booking cũ còn tham chiếu tới.

### JWT stateless, roles trong token
Token sống 8 giờ (một ngày làm việc). Đánh đổi: vô hiệu hóa user không thu hồi token đang có,
token cũ vẫn hợp lệ tới khi hết hạn, nhưng tài khoản bị khóa không thể tạo, sửa hay hủy booking (kiểm tra trong service);
đổi role cũng chỉ có hiệu lực từ lần đăng nhập sau.
Cải tiến khi lên production: token ngắn hạn + refresh token, hoặc kiểm tra `active` mỗi request.

### Giới hạn đã biết
- Khóa tài khoản không tự hủy các booking sắp tới của người đó; ADMIN tìm (`GET /bookings?userId=`) và hủy nếu cần.

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

| Method | Path | Quyền |
|---|---|---|
| POST | `/auth/login` | Public |
| GET | `/auth/me` | User |
| GET | `/rooms?includeInactive=` | User (`includeInactive` chỉ có tác dụng với Admin) |
| GET | `/rooms/{id}` | User |
| POST/PUT | `/rooms`, `/rooms/{id}` | Admin |
| PATCH | `/rooms/{id}/status` | Admin |
| GET | `/rooms/availability?start=&end=&minCapacity=&equipment=` | User (phòng nhỏ nhất vừa đủ đứng đầu; `+` của offset phải gửi là `%2B`) |
| GET | `/rooms/{id}/schedule?from=&to=` | User (mặc định 7 ngày từ hôm nay, tối đa 31) |
| GET | `/schedule?date=` | User (mọi phòng đang hoạt động trong ngày) |
| POST | `/bookings` | User |
| GET/PUT | `/bookings/{id}` | Owner/Admin (PUT cần gửi `version` vừa đọc) |
| POST | `/bookings/{id}/cancel` | Owner/Admin |
| GET | `/bookings/me?includePast=&status=&page=&size=` | User (mặc định: sắp tới + đang diễn ra) |
| GET | `/bookings?roomId=&userId=&status=&from=&to=&page=&size=` | Admin |
| GET/POST | `/users?q=&role=&active=`, `/users` | Admin |
| GET/PUT | `/users/{id}` (tên, role) | Admin |
| PATCH | `/users/{id}/status` (khóa / mở khóa, không xóa) | Admin |
| PUT | `/users/{id}/password` (đặt lại mật khẩu) | Admin |
| PUT | `/users/me/password` (tự đổi mật khẩu) | User |

---

## 8. Kiểm thử

100 test tự động (`./mvnw verify`): 30 unit test và 70 integration test trên PostgreSQL thật (Testcontainers).
GitHub Actions (`.github/workflows/ci.yml`) chạy toàn bộ mỗi lần push, rồi dựng `docker compose up --build`
và chạy collection Postman trên app thật.

| Test | Số case | Nội dung |
|---|---|---|
| `BookingPolicyTest` | 21 | R2–R8, R11 với `Clock` cố định: biên đúng lúc này, đúng 90 ngày, 07:00 / 20:00, giờ UTC và giờ VN |
| `RoomServiceTest` | 2 | Tạo phòng trùng tên đồng thời (unique violation) → 409, lỗi DB khác không bị che |
| `UserServiceTest` | 4 | Không hạ quyền / khóa admin cuối cùng; admin không tự khóa mình |
| `DemoBookingSeederTest` | 3 | Booking demo luôn thỏa `BookingPolicy` và không trùng nhau (sức chứa đọc từ SQL seed phòng) |
| `NoOverlapConstraintIT` | 7 | Ràng buộc DB: chồng lấn bị chặn, nối tiếp hợp lệ, hủy giải phóng slot, **20 luồng đồng thời chỉ 1 thành công** |
| `BookingApiIT` | 18 | Tạo / sửa / hủy qua HTTP: 409 kèm người giữ slot, 422 kèm số hiệu luật, quyền chủ / admin, `version` cũ → 409, **10 request đồng thời → 1 booking** |
| `BookingListIT` | 6 | Booking của tôi (sắp tới / lịch sử / trạng thái), tìm kiếm của admin, phân trang |
| `AvailabilityIT` | 7 | Phòng trống: chồng lấn một phần, nối tiếp, booking đã hủy, sức chứa, thiết bị, thứ tự |
| `ScheduleIT` | 6 | Lịch phòng, lịch ngày, ranh giới nửa đêm theo giờ VN, ẩn mô tả với người khác |
| `RoomApiIT` | 11 | CRUD phòng, quyền admin, trùng tên, khóa phòng |
| `UserApiIT` | 10 | Tạo tài khoản rồi đăng nhập được, trùng email, khóa / mở khóa, đổi và đặt lại mật khẩu |
| `AuthFlowIT` | 5 | Đăng nhập, dùng token, lỗi 400/401 đúng định dạng RFC 7807 |

Ngoài ra, collection Postman (`postman/`, mục 1) chạy 70 request, 157 assertion trên app thật dựng bằng `docker compose`.

---

## 9. Hướng phát triển khi đưa vào production
- Thông báo email/Slack khi được mời họp hoặc bị hủy
- Tích hợp SSO của công ty (OIDC), bỏ đăng nhập mật khẩu
- Check-in và tự giải phóng phòng nếu không check-in sau 10–15 phút
- Audit log, rate limiting, metrics (Micrometer + Prometheus)
- Refresh token / thu hồi token
- Booking lặp lại hằng tuần
- Khi khóa tài khoản, đề xuất hủy hàng loạt các booking sắp tới của người đó
