use parking_lot::Mutex;
use reqwest::blocking::{Client, RequestBuilder, Response};
use reqwest::blocking::multipart::{Form, Part};
use reqwest::header::{COOKIE, RANGE, SET_COOKIE};
use reqwest::{Method, StatusCode};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use std::sync::Arc;
use std::fs::File;
use std::io::copy;
use std::time::Duration;
use url::Url;

#[derive(Debug, thiserror::Error)]
pub enum LumosError {
    #[error("服务器地址无效")]
    InvalidAddress,
    #[error("服务器 API 版本不兼容")]
    IncompatibleServer,
    #[error("登录状态已失效")]
    Unauthorized,
    #[error("连接服务器超时")]
    Timeout,
    #[error("无法连接服务器")]
    Network,
    #[error("服务器请求失败")]
    Server,
    #[error("服务器响应无效")]
    InvalidResponse,
    #[error("无法读取数据")]
    Io,
}

#[derive(Clone, Deserialize)]
pub struct ServerInfo {
    pub name: String,
    pub version: String,
    pub api_version: u32,
    pub auth_required: bool,
    #[serde(default)]
    pub formats: Vec<String>,
}

#[derive(Clone, Deserialize)]
pub struct SessionState {
    #[serde(default)]
    pub auth_required: bool,
    pub authenticated: bool,
}

#[derive(Clone, Deserialize)]
pub struct Book {
    pub id: String,
    pub title: String,
    pub file_name: String,
    #[serde(default)]
    pub author: String,
    pub format: String,
    #[serde(default)]
    pub shelf: String,
    #[serde(default)]
    pub shelf_kind: String,
    #[serde(default)]
    pub category: String,
    #[serde(default)]
    pub series: String,
    #[serde(default)]
    pub is_comic: bool,
    #[serde(default)]
    pub fixed_layout: bool,
    #[serde(default)]
    pub page_direction: String,
    pub size: u64,
    pub modified: String,
    #[serde(default)]
    pub progress: f64,
    #[serde(default)]
    pub locator: String,
    #[serde(default)]
    pub progress_time: String,
    #[serde(default)]
    pub cover_url: String,
}

#[derive(Clone, Deserialize)]
pub struct ComicPage {
    pub index: u32,
    pub name: String,
    pub url: String,
}

#[derive(Clone, Deserialize)]
pub struct Progress {
    pub book_id: String,
    pub position: f64,
    #[serde(default)]
    pub locator: String,
    #[serde(default)]
    pub updated_at: String,
}

#[derive(Clone, Deserialize)]
pub struct ServerFont {
    pub name: String,
    pub url: String,
    pub size: u64,
}

#[derive(Clone, Deserialize, Serialize)]
pub struct Bookshelf {
    pub name: String,
    pub path: String,
    pub kind: String,
}

#[derive(Clone, Deserialize)]
pub struct ShelvesState {
    #[serde(default, deserialize_with = "null_default")]
    pub shelves: Vec<Bookshelf>,
    #[serde(default, deserialize_with = "null_default")]
    pub directories: Vec<String>,
    #[serde(default)]
    pub automatic: bool,
}

fn null_default<'de, D, T>(deserializer: D) -> Result<T, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de> + Default,
{
    Ok(Option::<T>::deserialize(deserializer)?.unwrap_or_default())
}

#[derive(Clone, Deserialize)]
pub struct ReadingDay {
    pub date: String,
    pub seconds: u32,
}

#[derive(Clone, Deserialize)]
pub struct BookReading {
    pub book_id: String,
    #[serde(default)]
    pub title: String,
    pub seconds: u32,
}

#[derive(Clone, Deserialize)]
pub struct ReadingStats {
    pub total_seconds: u64,
    pub today_seconds: u64,
    #[serde(default, deserialize_with = "null_default")]
    pub days: Vec<ReadingDay>,
    #[serde(default, deserialize_with = "null_default")]
    pub books: Vec<BookReading>,
}

#[derive(Deserialize)]
struct BooksResponse {
    #[serde(default)]
    books: Vec<Book>,
}

#[derive(Deserialize)]
struct PagesResponse {
    #[serde(default)]
    pages: Vec<ComicPage>,
}

#[derive(Deserialize)]
struct FontsResponse {
    #[serde(default)]
    fonts: Vec<ServerFont>,
}

#[derive(Serialize)]
struct LoginBody<'a> {
    password: &'a str,
}

#[derive(Serialize)]
struct ProgressBody<'a> {
    position: f64,
    locator: &'a str,
}

#[derive(Serialize)]
struct ReadingTimeBody {
    seconds: u32,
}

#[derive(Serialize)]
struct ShelvesBody {
    shelves: Vec<Bookshelf>,
}

pub struct ApiClient {
    base_url: Url,
    http: Client,
    session: Mutex<Option<String>>,
}

pub fn create_client(base_url: String, session_cookie: Option<String>) -> Result<Arc<ApiClient>, LumosError> {
    let base_url = normalize_base_url(&base_url)?;
    let http = Client::builder()
        .connect_timeout(Duration::from_secs(8))
        .timeout(Duration::from_secs(30))
        .user_agent("LumosReader-Android/0.1")
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|_| LumosError::Network)?;
    Ok(Arc::new(ApiClient {
        base_url,
        http,
        session: Mutex::new(session_cookie.filter(|value| !value.is_empty())),
    }))
}

impl ApiClient {
    pub fn base_url(&self) -> String {
        self.base_url.as_str().trim_end_matches('/').to_owned()
    }

    pub fn session_cookie(&self) -> Option<String> {
        self.session.lock().clone()
    }

    pub fn discover(&self) -> Result<ServerInfo, LumosError> {
        let info: ServerInfo = self.json(Method::GET, "/api/server", None::<&()>)?;
        if info.api_version != 4 {
            return Err(LumosError::IncompatibleServer);
        }
        Ok(info)
    }

    pub fn session(&self) -> Result<SessionState, LumosError> {
        self.json(Method::GET, "/api/session", None::<&()>)
    }

    pub fn login(&self, password: String) -> Result<SessionState, LumosError> {
        self.json(Method::POST, "/api/session", Some(&LoginBody { password: &password }))
    }

    pub fn logout(&self) -> Result<(), LumosError> {
        self.empty(Method::DELETE, "/api/session", None::<&()>)?;
        *self.session.lock() = None;
        Ok(())
    }

    pub fn books(&self) -> Result<Vec<Book>, LumosError> {
        Ok(self.json::<BooksResponse, ()>(Method::GET, "/api/books", None)?.books)
    }

    pub fn scan(&self) -> Result<(), LumosError> {
        self.empty(Method::POST, "/api/scan", None::<&()>)
    }

    pub fn comic_pages(&self, book_id: String) -> Result<Vec<ComicPage>, LumosError> {
        let path = format!("/api/books/{book_id}/pages");
        Ok(self.json::<PagesResponse, ()>(Method::GET, &path, None)?.pages)
    }

    pub fn progress(&self, book_id: String) -> Result<Progress, LumosError> {
        self.json(Method::GET, &format!("/api/books/{book_id}/progress"), None::<&()>)
    }

    pub fn save_progress(&self, book_id: String, position: f64, locator: String) -> Result<Progress, LumosError> {
        if !(0.0..=1.0).contains(&position) || locator.len() > 2048 {
            return Err(LumosError::InvalidResponse);
        }
        self.json(
            Method::PUT,
            &format!("/api/books/{book_id}/progress"),
            Some(&ProgressBody { position, locator: &locator }),
        )
    }

    pub fn add_reading_time(&self, book_id: String, seconds: u32) -> Result<(), LumosError> {
        if !(1..=300).contains(&seconds) {
            return Err(LumosError::InvalidResponse);
        }
        self.empty(
            Method::POST,
            &format!("/api/books/{book_id}/reading-time"),
            Some(&ReadingTimeBody { seconds }),
        )
    }

    pub fn stats(&self) -> Result<ReadingStats, LumosError> {
        self.json(Method::GET, "/api/stats", None::<&()>)
    }

    pub fn fonts(&self) -> Result<Vec<ServerFont>, LumosError> {
        Ok(self.json::<FontsResponse, ()>(Method::GET, "/api/fonts", None)?.fonts)
    }

    pub fn upload_font(&self, file_path: String, file_name: String) -> Result<(), LumosError> {
        let file = File::open(file_path).map_err(|_| LumosError::Io)?;
        let size = file.metadata().map_err(|_| LumosError::Io)?.len();
        if size == 0 || size > 32 << 20 || !matches!(file_name.to_lowercase().rsplit('.').next(), Some("ttf" | "otf" | "woff" | "woff2")) {
            return Err(LumosError::InvalidResponse);
        }
        let part = Part::reader_with_length(file, size).file_name(file_name);
        let request = self.request(Method::POST, "/api/fonts")?.multipart(Form::new().part("font", part));
        self.send(request)?;
        Ok(())
    }

    pub fn shelves(&self) -> Result<ShelvesState, LumosError> {
        self.json(Method::GET, "/api/shelves", None::<&()>)
    }

    pub fn save_shelves(&self, shelves: Vec<Bookshelf>) -> Result<(), LumosError> {
        self.empty(Method::PUT, "/api/shelves", Some(&ShelvesBody { shelves }))
    }

    pub fn get_bytes(&self, relative_url: String) -> Result<Vec<u8>, LumosError> {
        let response = self.send(self.request(Method::GET, &relative_url)?)?;
        response.bytes().map(|bytes| bytes.to_vec()).map_err(|_| LumosError::Io)
    }

    pub fn get_range(&self, relative_url: String, start: u64, end_inclusive: u64) -> Result<Vec<u8>, LumosError> {
        if end_inclusive < start {
            return Err(LumosError::InvalidResponse);
        }
        let request = self.request(Method::GET, &relative_url)?.header(RANGE, format!("bytes={start}-{end_inclusive}"));
        let response = self.send(request)?;
        if response.status() != StatusCode::PARTIAL_CONTENT {
            return Err(LumosError::InvalidResponse);
        }
        response.bytes().map(|bytes| bytes.to_vec()).map_err(|_| LumosError::Io)
    }

    pub fn download(&self, relative_url: String, destination: String) -> Result<(), LumosError> {
        let mut response = self.send(self.request(Method::GET, &relative_url)?)?;
        let mut file = File::create(destination).map_err(|_| LumosError::Io)?;
        copy(&mut response, &mut file).map_err(|_| LumosError::Io)?;
        Ok(())
    }

    fn json<T: DeserializeOwned, B: Serialize + ?Sized>(&self, method: Method, path: &str, body: Option<&B>) -> Result<T, LumosError> {
        let mut request = self.request(method, path)?;
        if let Some(body) = body {
            request = request.json(body);
        }
        self.send(request)?.json().map_err(|_| LumosError::InvalidResponse)
    }

    fn empty<B: Serialize + ?Sized>(&self, method: Method, path: &str, body: Option<&B>) -> Result<(), LumosError> {
        let mut request = self.request(method, path)?;
        if let Some(body) = body {
            request = request.json(body);
        }
        self.send(request)?;
        Ok(())
    }

    fn request(&self, method: Method, path: &str) -> Result<RequestBuilder, LumosError> {
        let url = self.resolve(path)?;
        let mut request = self.http.request(method, url);
        if let Some(session) = self.session.lock().as_ref() {
            request = request.header(COOKIE, format!("lumos_session={session}"));
        }
        Ok(request)
    }

    fn resolve(&self, path: &str) -> Result<Url, LumosError> {
        let url = self.base_url.join(path).map_err(|_| LumosError::InvalidAddress)?;
        if url.scheme() != self.base_url.scheme()
            || url.host_str() != self.base_url.host_str()
            || url.port_or_known_default() != self.base_url.port_or_known_default()
        {
            return Err(LumosError::InvalidAddress);
        }
        Ok(url)
    }

    fn send(&self, request: RequestBuilder) -> Result<Response, LumosError> {
        let response = request.send().map_err(|error| {
            if error.is_timeout() { LumosError::Timeout } else { LumosError::Network }
        })?;
        if let Some(cookie) = response.headers().get(SET_COOKIE).and_then(|value| value.to_str().ok()) {
            if let Some(token) = cookie.strip_prefix("lumos_session=").and_then(|value| value.split(';').next()) {
                *self.session.lock() = (!token.is_empty()).then(|| token.to_owned());
            }
        }
        match response.status() {
            StatusCode::UNAUTHORIZED => Err(LumosError::Unauthorized),
            status if status.is_success() => Ok(response),
            _ => Err(LumosError::Server),
        }
    }
}

fn normalize_base_url(input: &str) -> Result<Url, LumosError> {
    let trimmed = input.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return Err(LumosError::InvalidAddress);
    }
    let candidate = if trimmed.contains("://") {
        trimmed.to_owned()
    } else {
        format!("http://{trimmed}")
    };
    let mut url = Url::parse(&candidate).map_err(|_| LumosError::InvalidAddress)?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || !matches!(url.path(), "" | "/")
    {
        return Err(LumosError::InvalidAddress);
    }
    url.set_path("/");
    Ok(url)
}

uniffi::include_scaffolding!("lumos_core");

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_root_address() {
        assert_eq!(normalize_base_url(" http://nas.example:7767/ ").unwrap().as_str(), "http://nas.example:7767/");
        assert_eq!(normalize_base_url("nas.example:7767").unwrap().as_str(), "http://nas.example:7767/");
    }

    #[test]
    fn rejects_page_and_credentials() {
        assert!(normalize_base_url("https://user:pass@example.com/app").is_err());
    }
}
