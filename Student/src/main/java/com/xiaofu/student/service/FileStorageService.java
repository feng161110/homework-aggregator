//本地文件存储（头像）
package com.xiaofu.student.service;

import com.xiaofu.student.common.BusinessException;
import com.xiaofu.student.common.ErrorCode;
import com.xiaofu.student.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    /** 允许的图片扩展名。 */
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp");
    /** 头像子目录。 */
    private static final String AVATAR_SUBDIR = "avatars";
    /** 对外访问前缀，与 WebMvcConfig 中映射一致。 */
    private static final String URL_PREFIX = "/uploads/";

    private final AppProperties appProperties;

    public FileStorageService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 保存头像文件，返回可对外访问的相对 URL（如 /uploads/avatars/xxxx.png）。
     */
    public String storeAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "请选择要上传的图片");
        }

        long maxBytes = appProperties.getUpload().getMaxAvatarSizeKb() * 1024;
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "头像不能超过 " + appProperties.getUpload().getMaxAvatarSizeKb() + " KB");
        }

        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "仅支持 jpg / jpeg / png / gif / webp 格式的图片");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "上传的文件不是有效的图片");
        }

        try {
            Path avatarDir = resolveRoot().resolve(AVATAR_SUBDIR);
            Files.createDirectories(avatarDir);
            String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Path target = avatarDir.resolve(filename).toAbsolutePath().normalize();
            file.transferTo(target);
            return URL_PREFIX + AVATAR_SUBDIR + "/" + filename;
        } catch (IOException e) {
            log.error("头像保存失败", e);
            throw new BusinessException(ErrorCode.FILE_INVALID, "图片保存失败，请稍后重试");
        }
    }

    /**
     * 删除旧的本地头像文件（best-effort，失败不影响主流程）。
     */
    public void deleteByUrl(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return;
        }
        try {
            Path root = resolveRoot();
            Path file = root.resolve(url.substring(URL_PREFIX.length())).normalize();
            if (file.startsWith(root)) {
                Files.deleteIfExists(file);
            }
        } catch (Exception e) {
            log.warn("删除旧头像失败 url={}, error={}", url, e.getMessage());
        }
    }

    /** 上传根目录的绝对路径。 */
    public Path resolveRoot() {
        return Paths.get(appProperties.getUpload().getDir()).toAbsolutePath().normalize();
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
