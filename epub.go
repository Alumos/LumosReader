package main

import (
	"archive/zip"
	"encoding/xml"
	"errors"
	"io"
	"net/url"
	"path"
	"strings"
)

const maxEPUBMetadataSize = 4 << 20

type epubMetadata struct {
	Title     string
	Author    string
	Series    string
	Cover     string
	Comic     bool
	Direction string
}

func readEPUBMetadata(filename string) (epubMetadata, error) {
	archive, err := zip.OpenReader(filename)
	if err != nil {
		return epubMetadata{}, err
	}
	defer archive.Close()

	container := findZipFile(archive.File, "META-INF/container.xml")
	if container == nil {
		return epubMetadata{}, errors.New("EPUB container.xml missing")
	}
	var descriptor struct {
		Rootfiles []struct {
			Path string `xml:"full-path,attr"`
		} `xml:"rootfiles>rootfile"`
	}
	if err := readXML(container, &descriptor); err != nil || len(descriptor.Rootfiles) == 0 {
		return epubMetadata{}, errors.New("EPUB package path missing")
	}
	opfPath := cleanArchivePath(descriptor.Rootfiles[0].Path)
	opf := findZipFile(archive.File, opfPath)
	if opf == nil {
		return epubMetadata{}, errors.New("EPUB package missing")
	}

	var pkg struct {
		Metadata struct {
			Titles   []string `xml:"title"`
			Creators []string `xml:"creator"`
			Series   []string `xml:"series"`
			Meta     []struct {
				Name     string `xml:"name,attr"`
				Content  string `xml:"content,attr"`
				Property string `xml:"property,attr"`
				Value    string `xml:",chardata"`
			} `xml:"meta"`
		} `xml:"metadata"`
		Manifest struct {
			Items []struct {
				ID         string `xml:"id,attr"`
				Href       string `xml:"href,attr"`
				MediaType  string `xml:"media-type,attr"`
				Properties string `xml:"properties,attr"`
			} `xml:"item"`
		} `xml:"manifest"`
		Spine struct {
			Direction string `xml:"page-progression-direction,attr"`
		} `xml:"spine"`
	}
	if err := readXML(opf, &pkg); err != nil {
		return epubMetadata{}, err
	}

	coverID, series, layout, bookType, writingMode := "", firstText(pkg.Metadata.Series), "", "", ""
	for _, meta := range pkg.Metadata.Meta {
		if strings.EqualFold(meta.Name, "cover") {
			coverID = meta.Content
		}
		if meta.Property == "belongs-to-collection" && series == "" {
			series = strings.TrimSpace(meta.Value)
		}
		if meta.Property == "rendition:layout" {
			layout = strings.TrimSpace(meta.Value)
		}
		if strings.EqualFold(meta.Name, "book-type") {
			bookType = meta.Content
		}
		if strings.EqualFold(meta.Name, "primary-writing-mode") {
			writingMode = meta.Content
		}
	}
	coverHref := ""
	for _, item := range pkg.Manifest.Items {
		isImage := strings.HasPrefix(item.MediaType, "image/")
		if isImage && (strings.Contains(" "+item.Properties+" ", " cover-image ") || item.ID == coverID) {
			coverHref = item.Href
			break
		}
	}
	if coverHref == "" {
		for _, item := range pkg.Manifest.Items {
			if strings.HasPrefix(item.MediaType, "image/") && strings.Contains(strings.ToLower(path.Base(item.Href)), "cover") {
				coverHref = item.Href
				break
			}
		}
	}
	coverPath := resolveArchivePath(opfPath, coverHref)
	if findZipFile(archive.File, coverPath) == nil {
		coverPath = ""
	}
	direction := strings.ToLower(pkg.Spine.Direction)
	if direction != "rtl" && direction != "ltr" {
		if strings.HasSuffix(strings.ToLower(writingMode), "-rl") {
			direction = "rtl"
		} else {
			direction = ""
		}
	}
	return epubMetadata{
		Title:     firstText(pkg.Metadata.Titles),
		Author:    strings.Join(nonEmpty(pkg.Metadata.Creators), "、"),
		Series:    series,
		Cover:     coverPath,
		Comic:     strings.EqualFold(layout, "pre-paginated") || strings.EqualFold(bookType, "comic"),
		Direction: direction,
	}, nil
}

func readXML(file *zip.File, target any) error {
	if file.UncompressedSize64 > maxEPUBMetadataSize {
		return errors.New("EPUB metadata is too large")
	}
	reader, err := file.Open()
	if err != nil {
		return err
	}
	defer reader.Close()
	return xml.NewDecoder(io.LimitReader(reader, maxEPUBMetadataSize)).Decode(target)
}

func findZipFile(files []*zip.File, name string) *zip.File {
	wanted := cleanArchivePath(name)
	for _, file := range files {
		if cleanArchivePath(file.Name) == wanted {
			return file
		}
	}
	for _, file := range files {
		if strings.EqualFold(cleanArchivePath(file.Name), wanted) {
			return file
		}
	}
	return nil
}

func resolveArchivePath(base, reference string) string {
	parsed, err := url.Parse(reference)
	if err != nil {
		return ""
	}
	decoded, err := url.PathUnescape(parsed.Path)
	if err != nil {
		return ""
	}
	return cleanArchivePath(path.Join(path.Dir(base), decoded))
}

func cleanArchivePath(value string) string {
	return strings.TrimPrefix(path.Clean(strings.ReplaceAll(value, "\\", "/")), "./")
}

func firstText(values []string) string {
	for _, value := range values {
		if value = strings.TrimSpace(value); value != "" {
			return value
		}
	}
	return ""
}

func nonEmpty(values []string) []string {
	result := make([]string, 0, len(values))
	for _, value := range values {
		if value = strings.TrimSpace(value); value != "" {
			result = append(result, value)
		}
	}
	return result
}
