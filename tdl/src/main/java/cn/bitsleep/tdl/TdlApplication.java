package cn.bitsleep.tdl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TdlApplication {

    public static void main(String[] args) {
        SpringApplication.run(TdlApplication.class, args);
    }

}
