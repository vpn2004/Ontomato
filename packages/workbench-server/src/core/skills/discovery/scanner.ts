import fs from "fs";
import path from "path";
import type { SkillCategoryValue, SkillTypeValue } from "../constants";
import { SkillCategory, SkillType } from "../constants";
import {
  assertSkillPackageComplete,
  migrateLegacySkillManifest,
  readSkillMarkdown,
  readSkillLabels,
  parseSkillMarkdownFrontmatter,
  SkillManifestSchema,
} from "../manifest";
import type { RuntimeConfig, SkillMeta } from "../types";
import { createLogger } from "../../../logging/logger";

const logger = createLogger("scanner");

export class SkillScanner {
  private cache: SkillMeta[] | null = null;

  constructor(
    private readonly config: RuntimeConfig,
    private readonly isPublic = false,
    private readonly labelsPath?: string,
    private readonly translationsRoot?: string
  ) {}

  async scan(): Promise<SkillMeta[]> {
    if (this.config.enableCache && this.cache) {
      return this.cache;
    }

    const skills = [
      ...this.scanTypeDirectory(SkillType.EXECUTABLE),
      ...this.scanTypeDirectory(SkillType.KNOWLEDGE),
    ];
    this.assertUniqueIds(skills);

    if (this.labelsPath) {
      const labels = readSkillLabels(this.labelsPath);
      for (const skill of skills) {
        const text = labels[skill.id];
        if (text) skill.manifest = { ...skill.manifest, ...text };
      }
    }

    this.cache = skills;
    return this.cache;
  }

  private scanTypeDirectory(type: SkillTypeValue): SkillMeta[] {
    const typeRoot = path.join(this.config.skillsRoot, type);
    if (!fs.existsSync(typeRoot)) return [];

    const skills: SkillMeta[] = [];
    const categories = fs
      .readdirSync(typeRoot, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name);

    for (const category of categories) {
      if (!this.isSkillCategory(category)) {
        logger.warn(`[Scanner] Ignoring unsupported skill category: ${category}`);
        continue;
      }

      const categoryPath = path.join(typeRoot, category);
      const skillNames = fs
        .readdirSync(categoryPath, { withFileTypes: true })
        .filter((entry) => entry.isDirectory())
        .map((entry) => entry.name);

      for (const skillName of skillNames) {
        const skill = this.scanSkill(path.join(categoryPath, skillName), skillName, type, category);
        if (skill) skills.push(skill);
      }
    }

    return skills;
  }

  private scanSkill(
    skillPath: string,
    skillName: string,
    type: SkillTypeValue,
    category: SkillCategoryValue
  ): SkillMeta | null {
    const skillMdPath = path.join(skillPath, "SKILL.md");

    try {
      if (!this.isPublic) migrateLegacySkillManifest(skillPath);
      if (!fs.existsSync(skillMdPath)) {
        logger.warn(`[Scanner] Ignoring ${skillName}: SKILL.md frontmatter is required`);
        return null;
      }

      const parsed = readSkillMarkdown(skillMdPath);
      if (!parsed) {
        logger.warn(`[Scanner] Ignoring ${skillName}: SKILL.md frontmatter is required`);
        return null;
      }
      const { manifest, content } = parsed;
      if (manifest.type !== type) {
        logger.warn(
          `[Scanner] Ignoring ${manifest.name}: frontmatter type ${manifest.type} does not match directory ${type}`
        );
        return null;
      }
      if (manifest.category !== category) {
        logger.warn(
          `[Scanner] Ignoring ${manifest.name}: frontmatter category ${manifest.category} does not match directory ${category}`
        );
        return null;
      }

      assertSkillPackageComplete(
        manifest,
        (relativePath) => {
          const requiredPath = path.join(skillPath, relativePath);
          return fs.existsSync(requiredPath) && fs.statSync(requiredPath).isFile();
        },
        content
      );

      let localizedManifest = manifest;
      let localizedPath = skillMdPath;
      if (this.isPublic && this.translationsRoot) {
        const overlayPath = path.join(this.translationsRoot, path.relative(this.config.skillsRoot, skillMdPath));
        if (fs.existsSync(overlayPath)) {
          const overlay = parseSkillMarkdownFrontmatter(fs.readFileSync(overlayPath, "utf8"));
          if (!overlay || !overlay.frontmatter || typeof overlay.frontmatter !== "object") {
            throw new Error(`Invalid skill translation: ${overlayPath}`);
          }
          const fields = overlay.frontmatter as Record<string, unknown>;
          const patch = Object.fromEntries(
            ["title", "description", "tags", "useCases", "defaultEnabled"]
              .filter((key) => Object.hasOwn(fields, key)).map((key) => [key, fields[key]])
          );
          localizedManifest = SkillManifestSchema.parse({ ...manifest, ...patch });
          localizedPath = overlayPath;
        }
      }
      return {
        id: manifest.name,
        isPublic: this.isPublic,
        path: skillPath,
        manifest: localizedManifest,
        skillMdPath: localizedPath,
      };
    } catch (error) {
      logger.warn(`[Scanner] Failed to scan ${skillName}:`, error);
      return null;
    }
  }

  private isSkillCategory(value: string): value is SkillCategoryValue {
    return value === SkillCategory.ANALYSIS || value === SkillCategory.VISUALIZATION;
  }

  private assertUniqueIds(skills: SkillMeta[]): void {
    const pathsById = new Map<string, string>();
    for (const skill of skills) {
      const existingPath = pathsById.get(skill.id);
      if (existingPath) {
        throw new Error(
          `Duplicate skill id "${skill.id}" found in ${existingPath} and ${skill.path}`
        );
      }
      pathsById.set(skill.id, skill.path);
    }
  }

  clearCache(): void {
    this.cache = null;
  }
}
