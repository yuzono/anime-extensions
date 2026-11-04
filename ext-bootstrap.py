#!/usr/bin/env python3

import argparse
import re
from pathlib import Path
from urllib.parse import urlparse


def ascii_validator(value: str) -> str:
    value = value.strip()
    try:
        value.encode("ascii")
    except UnicodeEncodeError:
        raise argparse.ArgumentTypeError(f"'{value}' is not ASCII")

    return value


def validate_args(args: argparse.Namespace, parser: argparse.ArgumentParser):
    # Validate language
    lang = args.lang
    if not re.fullmatch(r"(?:[a-z]{2,3}(?:-(?:[A-Z]{2,3}|[A-Za-z]{4}))?|all)", lang):
        parser.error(
            f"invalid language: '{lang}' (must be a 2- or 3-letter ISO language code, "
            "optionally with a region or script subtag such as 'pt-BR' or 'zh-hant')"
        )

    # Validate base URL
    baseurl = urlparse(args.baseurl)
    if baseurl.scheme != "https" or not baseurl.netloc:
        parser.error(
            f"invalid baseurl: '{baseurl}' (must include scheme 'https://' and a host)"
        )
    args.baseurl = f"{baseurl.scheme}://{baseurl.netloc}"

    # Validate extension repo path
    path = Path(args.path).resolve()
    if not path.is_dir():
        parser.error(f"invalid path: '{path}' (directory does not exist)")
    elif "common" not in [p.name for p in path.iterdir()]:
        parser.error(f"invalid path: '{path}' (not a valid extension repo directory)")
    args.path = path

    # Validate multisrc theme if provided
    args.lib_version = "16"
    args.multisrc_args = []
    if args.multisrc:
        multisrc_dir = path / "lib-multisrc"
        if not multisrc_dir.exists() or not multisrc_dir.is_dir():
            parser.error(f"No multisrc found in extension repo path: '{path}'")

        multisrc_theme = args.multisrc.lower()
        if multisrc_theme not in [p.name for p in multisrc_dir.iterdir() if p.is_dir()]:
            parser.error(
                f"invalid multisrc theme: '{multisrc_theme}' (not found in '{multisrc_dir}')"
            )
        args.multisrc = multisrc_theme

        # A themed source inherits the theme's API, so it uses the theme's lib version
        args.lib_version = re.search(
            r'libVersion = "(\d+)"',
            (multisrc_dir / multisrc_theme / "build.gradle.kts").read_text(),
        ).group(1)

        args.multisrc_class, args.multisrc_args = get_multisrc_class(
            multisrc_dir / multisrc_theme, multisrc_theme, parser
        )


def get_ext_classname(extname: str) -> str:
    sanitized = re.sub(r"[^A-Za-z0-9 ]", "", extname)
    return "".join(word.capitalize() for word in re.findall(r"[A-Za-z0-9]+", sanitized))


def get_multisrc_class(
    theme_dir: Path, theme: str, parser: argparse.ArgumentParser
) -> tuple[str, list[tuple[str, str]]]:
    # The theme's class name doesn't always follow its directory (dooplay -> DooPlay),
    # so read it, with a placeholder for each constructor parameter that has no default
    package_dir = theme_dir / "src" / "eu" / "kanade" / "tachiyomi" / "multisrc" / theme
    for file in sorted(package_dir.glob("*.kt")):
        text = re.sub(r"//[^\n]*", "", file.read_text())
        match = re.search(r"^abstract class (\w+)\s*(\()?", text, re.MULTILINE)
        if match:
            break
    else:
        parser.error(
            f"invalid multisrc theme: '{theme}' (no abstract class in '{package_dir}')"
        )

    classname = match.group(1)
    if not match.group(2):
        return classname, []

    # Split the constructor's parameter list at top-level commas
    params, current, depth = [], "", 0
    for char in text[match.end() :].replace("->", "→"):
        if char in "(<[{":
            depth += 1
        elif char in ")>]}":
            if depth == 0:
                break
            depth -= 1
        elif char == "," and depth == 0:
            params.append(current)
            current = ""
            continue
        current += char
    params.append(current)

    ctor_args = []
    for param in filter(str.strip, params):
        declaration, _, default = param.partition("=")
        if default.strip():
            continue

        name, _, type_ = declaration.partition(":")
        *modifiers, name = name.split()
        if "override" in modifiers:
            parser.error(
                f"invalid multisrc theme: '{theme}' ({classname} takes `{name}` "
                "in its constructor, which the source block owns)"
            )

        type_ = type_.strip()
        if type_ == "String":
            ctor_args.append((name, '""'))
        elif type_.startswith("List<"):
            ctor_args.append((name, "emptyList()"))
        else:
            ctor_args.append((name, f'TODO("{name}")'))

    return classname, ctor_args


if __name__ == "__main__":
    argparser = argparse.ArgumentParser()
    argparser.add_argument(
        "-n", "--extname", type=ascii_validator, help="Extension name", required=True
    )
    argparser.add_argument(
        "--source-name",
        type=str,
        help="Source Name (defaults to extname)",
        required=False,
    )
    argparser.add_argument(
        "-l",
        "--lang",
        "--language",
        type=ascii_validator,
        help="Extension language",
        required=True,
    )
    argparser.add_argument(
        "-u",
        "--baseurl",
        type=ascii_validator,
        help="Extension BaseUrl (must be an https URL)",
        required=True,
    )
    argparser.add_argument(
        "-c",
        "--content-warning",
        choices=["SAFE", "MIXED", "NSFW"],
        type=str.upper,
        default="SAFE",
        help="Content warning level",
    )
    argparser.add_argument(
        "-m",
        "--multisrc",
        type=ascii_validator,
        help="multisrc theme",
        required=False,
    )
    argparser.add_argument(
        "--path",
        type=str,
        help="Path to extension repo directory (defaults to cwd)",
        required=False,
        default=".",
    )
    args = argparser.parse_args()
    validate_args(args, argparser)

    ext_dir_name = re.sub(r"[^A-Za-z0-9]", "", args.extname).lower()
    ext_dir_lang = args.lang.split("-")[0]
    ext_dir = args.path / "src" / ext_dir_lang / ext_dir_name

    if ext_dir.exists():
        print(f"Extension directory already exists: '{ext_dir}'")
        exit(1)

    ext_dir.mkdir(parents=True)
    print(f"Created extension directory: '{ext_dir}'")

    with open(ext_dir / "build.gradle.kts", "w") as f:
        f.write("import io.github.keiyoushi.gradle.api.ContentWarning\n\n")
        f.write("plugins {\n")
        f.write("\talias(kei.plugins.extension)\n")
        f.write("}\n\n")
        f.write("yuzono {\n")
        f.write(f'\tname = "{args.extname}"\n')

        if args.multisrc:
            f.write(f'\ttheme = "{args.multisrc}"\n')
            f.write("\tversionCode = 0\n")
        else:
            f.write("\tversionCode = 1\n")

        f.write(f"\tcontentWarning = ContentWarning.{args.content_warning}\n")
        f.write(f'\tlibVersion = "{args.lib_version}"\n\n')

        f.write("\tsource {\n")
        if args.source_name:
            f.write(f'\t\tname = "{args.source_name}"\n')
        f.write(f'\t\tlang = "{args.lang}"\n')
        f.write(f'\t\tbaseUrl = "{args.baseurl}"\n')
        f.write("\t}\n")

        if not args.multisrc:
            f.write("\n\tdeeplink {\n")
            f.write('\t\tpath("/..*")\n')
            f.write("\t}\n")

        f.write("}\n")

    ext_package_dir = (
        ext_dir
        / "src"
        / "eu"
        / "kanade"
        / "tachiyomi"
        / "animeextension"
        / ext_dir_lang
        / ext_dir_name
    )
    ext_package_dir.mkdir(parents=True)
    print(f"Created extension package directory: '{ext_package_dir}'")

    with open(ext_package_dir / f"{get_ext_classname(args.extname)}.kt", "w") as f:
        f.write(
            f"""package eu.kanade.tachiyomi.animeextension.{ext_dir_lang}.{ext_dir_name}\n\n"""
        )

        if args.multisrc:
            f.write(
                f"import eu.kanade.tachiyomi.multisrc.{args.multisrc}.{args.multisrc_class}\n"
            )
            f.write("import keiyoushi.annotation.Source\n\n")
            f.write("@Source\n")
            ctor_args = "".join(
                f"\t{name} = {value},\n" for name, value in args.multisrc_args
            )
            f.write(
                f"abstract class {get_ext_classname(args.extname)} : {args.multisrc_class}("
                + (f"\n{ctor_args}" if ctor_args else "")
                + ")\n"
            )
        else:
            f.write("import eu.kanade.tachiyomi.animesource.model.AnimeFilterList\n")
            f.write("import eu.kanade.tachiyomi.animesource.model.AnimesPage\n")
            f.write("import eu.kanade.tachiyomi.animesource.model.Hoster\n")
            f.write("import eu.kanade.tachiyomi.animesource.model.SAnime\n")
            f.write("import eu.kanade.tachiyomi.animesource.model.SEpisode\n")
            f.write("import eu.kanade.tachiyomi.animesource.model.Video\n")
            f.write("import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource\n")
            f.write("import keiyoushi.annotation.Source\n")
            f.write("import okhttp3.Request\n")
            f.write("import okhttp3.Response\n")
            f.write("import java.lang.UnsupportedOperationException\n\n")

            f.write("@Source\n")
            f.write(
                f"abstract class {get_ext_classname(args.extname)} : AnimeHttpSource() {{\n\n"
            )
            f.write("\toverride val supportsLatest = true\n\n")
            f.write("\toverride val client = network.client\n\n")

            f.write("\toverride fun headersBuilder() = super.headersBuilder()\n")
            f.write('\t\t.set("Referer", "$baseUrl/")\n\n')

            f.write(
                "\toverride fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {\n"
            )
            f.write('\t\tif (query.startsWith("https://")) {\n')
            f.write('\t\t\tthrow Exception("Deeplink not implemented")\n')
            f.write("\t\t}\n\n")
            f.write("\t\treturn super.getSearchAnime(page, query, filters)\n")
            f.write("\t}\n\n")

            f.write(
                "\toverride fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun animeDetailsRequest(anime: SAnime): Request = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun getAnimeUrl(anime: SAnime): String = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun hosterListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun getEpisodeUrl(episode: SEpisode): String = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()\n\n"
            )

            f.write(
                "\toverride fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()\n"
            )

            f.write("}\n")

    if args.multisrc_args:
        names = ", ".join(name for name, _ in args.multisrc_args)
        print(f"Replace the placeholder {args.multisrc_class} constructor arguments: {names}")
