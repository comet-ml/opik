"""PDF report generation functions for usage report module."""

import os
import traceback
from typing import Any, Dict

from rich.console import Console

from .charts import create_individual_chart
from .statistics import calculate_statistics

console = Console()


def create_pdf_report(data: Dict[str, Any], output_dir: str = ".") -> str:
    """
    Create a PDF report with statistics page and individual chart pages.

    Args:
        data: The extracted data dictionary
        output_dir: Directory to save PDF (default: current directory)

    Returns:
        Path to saved PDF file
    """
    try:
        from reportlab.lib import colors
        from reportlab.lib.pagesizes import letter
        from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
        from reportlab.lib.units import inch
        from reportlab.platypus import (
            Image,
            PageBreak,
            Paragraph,
            SimpleDocTemplate,
            Spacer,
            Table,
            TableStyle,
        )
    except ImportError:
        raise ImportError(
            "reportlab is required for PDF report generation. "
            "Please install it with: pip install reportlab"
        )

    # Calculate statistics
    stats = calculate_statistics(data)

    # Create PDF
    pdf_filename = os.path.join(
        output_dir, f"opik_usage_report_{data['workspace']}.pdf"
    )
    doc = SimpleDocTemplate(pdf_filename, pagesize=letter)
    story = []

    # Get styles
    styles = getSampleStyleSheet()
    title_style = ParagraphStyle(
        "CustomTitle",
        parent=styles["Heading1"],
        fontSize=24,
        textColor=colors.HexColor("#1a1a1a"),
        spaceAfter=30,
        alignment=1,  # Center alignment
    )
    heading_style = ParagraphStyle(
        "CustomHeading",
        parent=styles["Heading2"],
        fontSize=16,
        textColor=colors.HexColor("#2c3e50"),
        spaceAfter=12,
    )

    # Title page / First page with statistics
    story.append(Paragraph("Opik Usage Report", title_style))
    story.append(Spacer(1, 0.3 * inch))

    # Statistics section
    story.append(Paragraph("Summary Statistics", heading_style))
    story.append(Spacer(1, 0.1 * inch))

    # Format dates for display
    extraction_date_str = "N/A"
    if stats["extraction_date"]:
        try:
            extraction_date_str = stats["extraction_date"][:10]
        except (TypeError, IndexError):
            extraction_date_str = (
                str(stats["extraction_date"])[:10]
                if stats["extraction_date"]
                else "N/A"
            )

    start_date_str = "N/A"
    end_date_str = "N/A"
    if stats["date_range"].get("start"):
        try:
            start_date_str = stats["date_range"]["start"][:10]
        except (TypeError, IndexError):
            start_date_str = (
                str(stats["date_range"]["start"])[:10]
                if stats["date_range"]["start"]
                else "N/A"
            )
    if stats["date_range"].get("end"):
        try:
            end_date_str = stats["date_range"]["end"][:10]
        except (TypeError, IndexError):
            end_date_str = (
                str(stats["date_range"]["end"])[:10]
                if stats["date_range"]["end"]
                else "N/A"
            )

    # Create statistics table
    stats_data = [
        ["Workspace", stats["workspace"]],
        ["Extraction Date", extraction_date_str],
        ["Date Range", f"{start_date_str} to {end_date_str}"],
        ["Aggregation Unit", stats["unit"].capitalize()],
        ["", ""],  # Separator row
        ["Total Projects", str(stats["total_projects"])],
        ["Projects with Data", str(stats["projects_with_data"])],
        ["Periods with Data", str(stats["periods_with_data"])],
        ["", ""],  # Separator row
        ["Total Experiments", f"{stats['total_experiments']:,}"],
        ["Total Datasets", f"{stats['total_datasets']:,}"],
        ["Total Traces", f"{stats['total_traces']:,.0f}"],
        ["Total Spans", f"{stats['total_spans']:,.0f}"],
        ["Total Tokens", f"{stats['total_tokens']:,.0f}"],
        ["Total Cost", f"${stats['total_cost']:,.2f}"],
    ]

    stats_table = Table(stats_data, colWidths=[2.5 * inch, 4 * inch])
    stats_table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (0, -1), colors.HexColor("#ecf0f1")),
                ("TEXTCOLOR", (0, 0), (-1, -1), colors.HexColor("#2c3e50")),
                ("ALIGN", (0, 0), (-1, -1), "LEFT"),
                ("FONTNAME", (0, 0), (0, -1), "Helvetica-Bold"),
                ("FONTNAME", (1, 0), (1, -1), "Helvetica"),
                ("FONTSIZE", (0, 0), (-1, -1), 10),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
                ("TOPPADDING", (0, 0), (-1, -1), 8),
                ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#bdc3c7")),
            ]
        )
    )

    story.append(stats_table)
    story.append(PageBreak())

    # Create individual charts and add to PDF
    chart_types = [
        ("trace_count", "Trace Count"),
        ("span_count", "Span Count"),
        ("token_count", "Token Count"),
        ("cost", "Cost"),
        ("experiment_count", "Experiment Count"),
        ("dataset_count", "Dataset Count"),
    ]

    chart_files_to_cleanup = []  # Keep track of files to delete after PDF is built

    for chart_type, chart_title in chart_types:
        try:
            chart_path = create_individual_chart(data, chart_type, output_dir)
            if chart_path:
                # Ensure path is absolute
                chart_path = os.path.abspath(chart_path)
                # Double-check file exists and is readable
                if os.path.exists(chart_path) and os.access(chart_path, os.R_OK):
                    # Add chart title
                    story.append(Paragraph(chart_title, heading_style))
                    story.append(Spacer(1, 0.1 * inch))

                    # Add chart image (legend is already included in the chart image below the chart)
                    try:
                        # Use absolute path and verify file is readable
                        if not os.path.exists(chart_path):
                            console.print(
                                f"[yellow]Warning: Chart file disappeared: {chart_path}[/yellow]"
                            )
                            continue

                        # All charts are exactly 14x8 inches (4200x2400 pixels at 300 DPI)
                        # Scale to fit page with margins
                        # Aspect ratio: 14/8 = 1.75 (always wider than tall)
                        max_width = 7.5 * inch  # Leave margin
                        chart_aspect_ratio = 14.0 / 8.0  # 1.75

                        # Charts are always wider than tall, so always scale by width
                        display_width = max_width
                        display_height = max_width / chart_aspect_ratio

                        # All charts use the same dimensions, so use fixed scaling
                        img = Image(
                            chart_path, width=display_width, height=display_height
                        )
                        story.append(img)
                        story.append(Spacer(1, 0.1 * inch))
                        story.append(PageBreak())

                        # Track file for cleanup after PDF is built
                        chart_files_to_cleanup.append(chart_path)
                    except Exception as img_error:
                        console.print(
                            f"[yellow]Warning: Could not add chart image {chart_title}: {img_error}[/yellow]"
                        )
                        # Try to clean up the file if we couldn't use it
                        try:
                            if os.path.exists(chart_path):
                                os.remove(chart_path)
                        except Exception:
                            pass
                else:
                    console.print(
                        f"[yellow]Warning: Chart file not found or not readable: {chart_path}[/yellow]"
                    )
            else:
                console.print(
                    f"[yellow]Warning: Could not create chart: {chart_title}[/yellow]"
                )
        except Exception as chart_error:
            console.print(
                f"[yellow]Warning: Error creating chart {chart_title}: {chart_error}[/yellow]"
            )
            traceback.print_exc()
            continue  # Skip this chart and continue with others

    # Build PDF (this is when reportlab actually reads the image files)
    try:
        doc.build(story)
    finally:
        # Clean up temporary chart files after PDF is built
        for chart_path in chart_files_to_cleanup:
            try:
                if os.path.exists(chart_path):
                    os.remove(chart_path)
            except Exception:
                pass  # Ignore cleanup errors

    return pdf_filename
