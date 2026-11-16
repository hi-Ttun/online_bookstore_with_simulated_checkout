package vn.edu.uth.bookstore;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BookstoreApplication {

    public static void main(String[] args) {
        // Chuẩn hóa múi giờ JVM về UTC; PostgreSQL 16 không nhận tên múi giờ cũ "Asia/Saigon" mà Windows trả về.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(BookstoreApplication.class, args);
    }
}
